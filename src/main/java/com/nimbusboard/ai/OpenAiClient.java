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
                You are an architecture diagram expert. Given a user prompt, generate a simple Mermaid flowchart.
                STRICT RULES:
                1. Start with "graph LR" (left to right).
                2. Use ONLY this syntax: ID[Label] --> ID[Label]
                3. DO NOT use labels on arrows (no -->|text|>).
                4. DO NOT use parentheses () or brackets {} for node shapes. Use only square brackets [].
                5. Use single character IDs for nodes (A, B, C, etc).
                Example:
                graph LR
                A[Client] --> B[API Gateway]
                B --> C[User Service]
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
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(b -> {
                                    log.error("OpenAI API 4xx error: {}", b);
                                    return Mono.error(new RuntimeException("OpenAI API error: " + b));
                                }))
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(b -> {
                                    log.error("OpenAI API 5xx error: {}", b);
                                    return Mono.error(new RuntimeException("OpenAI server error: " + b));
                                }))
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .filter(throwable -> throwable.getMessage() != null
                                && throwable.getMessage().contains("Rate limited")))
                .block(Duration.ofSeconds(60));

        if (responseBody == null || responseBody.isBlank()) {
            log.error("OpenAI returned an empty response");
            throw new RuntimeException("AI service returned empty response");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Log error if Gemini returns an error field
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                String errorMsg = errorNode.path("message").asText("Unknown AI error");
                log.error("OpenAI/Gemini error in response body: {}", errorMsg);
                throw new RuntimeException("AI service error: " + errorMsg);
            }

            // Log token usage
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                int totalTokens = usage.path("total_tokens").asInt(0);
                tokenCounter.increment(totalTokens);
                log.info("OpenAI token usage: {} tokens", totalTokens);
            }

            // Gemini sometimes uses delta instead of message in streaming-compatible mode
            JsonNode choices = root.path("choices");
            if (choices.isEmpty() || choices.size() == 0) {
                log.error("No choices in OpenAI response");
                throw new RuntimeException("AI service returned no choices");
            }

            JsonNode choice = choices.get(0);
            String content = null;

            // Try message.content first (standard OpenAI format)
            JsonNode messageNode = choice.path("message");
            if (!messageNode.isMissingNode()) {
                content = messageNode.path("content").asText(null);
            }

            // Fallback: try delta.content (Gemini streaming-compatible format)
            if (content == null) {
                JsonNode deltaNode = choice.path("delta");
                if (!deltaNode.isMissingNode()) {
                    content = deltaNode.path("content").asText(null);
                }
            }

            if (content == null || content.isBlank()) {
                log.error("Could not extract content from AI response. Raw: {}", responseBody);
                throw new RuntimeException("AI service returned empty content");
            }

            // Strip markdown fences if present
            content = content.strip();
            if (content.startsWith("```mermaid")) content = content.substring(10);
            if (content.startsWith("```")) content = content.substring(3);
            if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
            content = content.strip();

            log.debug("AI Mermaid output: {}", content.length() > 500 ? content.substring(0, 500) : content);

            return AiGenerateResponse.builder().mermaid(content).build();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse AI response: " + e.getMessage());
        }
    }
}
