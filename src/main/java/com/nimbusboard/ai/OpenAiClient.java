package com.nimbusboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusboard.ai.dto.AiGenerateResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxTokens;
    private final Counter tokenCounter;
    private final Counter requestCounter;

    public OpenAiClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${app.openai.api-key}") String apiKey,
            @Value("${app.openai.base-url}") String baseUrl,
            @Value("${app.openai.model}") String model,
            @Value("${app.openai.max-tokens}") int maxTokens) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxTokens = maxTokens;
        this.tokenCounter = Counter.builder("openai.tokens.used")
                .description("Total OpenAI tokens used")
                .register(meterRegistry);
        this.requestCounter = Counter.builder("openai.requests.total")
                .description("Total OpenAI API requests")
                .register(meterRegistry);
    }

    public AiGenerateResponse generate(String prompt) {
        String systemPrompt = """
                You are a whiteboard layout AI. Given a user prompt, generate a JSON object with two arrays:
                - "nodes": array of objects with { "id", "type" (rect|circle|text|sticky), "x", "y", "width", "height", "text", "fill" }
                - "edges": array of objects with { "id", "type" (arrow|line), "points" (array of numbers [x1,y1,x2,y2,...]), "stroke" }
                Return ONLY valid JSON, no markdown, no explanation.
                """;

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.7
        );

        requestCounter.increment();

        String responseBody = webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> {
                    if (response.statusCode().value() == 429) {
                        return Mono.error(new RuntimeException("Rate limited by OpenAI"));
                    }
                    return response.bodyToMono(String.class)
                            .flatMap(b -> Mono.error(new RuntimeException("OpenAI error: " + b)));
                })
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .filter(throwable -> throwable.getMessage() != null
                                && throwable.getMessage().contains("Rate limited")))
                .block(Duration.ofSeconds(60));

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Log token usage
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                int totalTokens = usage.path("total_tokens").asInt(0);
                tokenCounter.increment(totalTokens);
                log.info("OpenAI token usage: {} tokens", totalTokens);
            }

            String content = root.path("choices").get(0).path("message").path("content").asText();

            // Strip markdown fences if present
            content = content.strip();
            if (content.startsWith("```json")) content = content.substring(7);
            if (content.startsWith("```")) content = content.substring(3);
            if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
            content = content.strip();

            return objectMapper.readValue(content, AiGenerateResponse.class);
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response", e);
            throw new RuntimeException("Failed to parse AI response: " + e.getMessage());
        }
    }
}
