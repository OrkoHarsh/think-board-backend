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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
public class OpenAiClient {

    private static final String SYSTEM_PROMPT = """
            You are an architecture diagram expert. Given a user prompt, generate a high-quality Mermaid flowchart for a scalable system HLD.

            SYNTAX RULES (mandatory):
            1. Start with "graph LR" (left to right).
            2. Use ONLY this syntax: ID[Label] --> ID[Label]
            3. DO NOT use labels on arrows (no -->|text|).
            4. DO NOT use parentheses () or curly braces {} for node shapes. Use only square brackets [].
            5. Use single character IDs for nodes (A, B, C, ...).
            6. Output ONLY the Mermaid graph — no markdown fences, no commentary.

            ARCHITECTURE RULES (mandatory):
            1. Nodes are real components only: clients, CDNs, load balancers, API gateways, services, caches, databases, queues, workers. Never payloads or data (no "Original URL", "JWT", "HTML", "JSON").
            2. Edges follow request/data flow: Client → edge layer → app → dependencies. Never Database --> Cache or Database --> Service.
            3. Cache, DB, and queues are siblings under the owning service (Service --> Cache and Service --> DB), not chained through each other.
            4. Analytics, logging, and monitoring attach in parallel from the service — never as Analytics --> Logging --> Monitoring.
            5. For HLD/architecture prompts use: Client → CDN (optional) → Load Balancer → API Gateway → core service(s) → Cache + primary store (+ async worker/queue if relevant).
            6. Keep 6–12 nodes. One clear path. No redundant Web Server + Application Server layers unless the user asks for them.
            7. Name services for the user's domain (URL shortener, auth, chat, etc.), not a generic 3-tier template.

            EXAMPLES:

            Prompt: scalable HLD for URL shortener
            graph LR
            A[Client] --> B[CDN]
            B[CDN] --> C[Load Balancer]
            C[Load Balancer] --> D[API Gateway]
            D[API Gateway] --> E[URL Shortening Service]
            E[URL Shortening Service] --> F[Redis Cache]
            E[URL Shortening Service] --> G[PostgreSQL]
            E[URL Shortening Service] --> H[Analytics Service]

            Prompt: auth login architecture
            graph LR
            A[Client App] --> B[Load Balancer]
            B[Load Balancer] --> C[API Gateway]
            C[API Gateway] --> D[Auth Service]
            D[Auth Service] --> E[User Database]
            D[Auth Service] --> F[Token Store]
            C[API Gateway] --> G[Protected Services]

            Prompt: scalable microservice backend
            graph LR
            A[Client] --> B[Load Balancer]
            B[Load Balancer] --> C[API Gateway]
            C[API Gateway] --> D[Application Service]
            D[Application Service] --> E[Redis Cache]
            D[Application Service] --> F[Database]
            D[Application Service] --> G[Message Queue]
            G[Message Queue] --> H[Worker Service]
            """;

    private static final String RETRY_USER_MESSAGE = """
            Previous output broke the Mermaid syntax rules. Return ONLY a valid Mermaid graph.
            Must start with graph LR. Every other line must be exactly: ID[Label] --> ID[Label]
            Single-letter IDs only. No arrow labels, no (), no {}, no markdown fences, no commentary.

            Architecture constraints to obey:
            - Output 6–12 nodes and keep the graph mostly one-way (no cycles).
            - Do NOT create cache/database chains (no Cache --> Database, no Database --> Cache).
            - Do NOT create edges into the Client (no Notification --> Client, no Worker --> Client).
            - Do NOT use payload/data nodes (e.g. Original URL, JWT, HTML, JSON).
            """;

    /** Header line: graph LR (optional trailing whitespace). */
    private static final Pattern GRAPH_HEADER = Pattern.compile("^graph\\s+LR\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Edge line: single-letter ID[Label] --> single-letter ID[Label]
     * Second node may omit the bracket form when reusing an ID (B --> C) — require both with labels
     * per product rules: ID[Label] --> ID[Label]
     */
    private static final Pattern EDGE_LINE = Pattern.compile(
            "^[A-Z]\\[[^\\[\\](){}]+]\\s*-->\\s*[A-Z]\\[[^\\[\\](){}]+]$");

    private static final Pattern EDGE_LINE_CAPTURE = Pattern.compile(
            "^([A-Z])\\[([^\\[\\](){}]+)]\\s*-->\\s*([A-Z])\\[([^\\[\\](){}]+)]$");

    private static boolean isCacheLabel(String labelLower) {
        return labelLower.contains("cache") || labelLower.contains("redis");
    }

    private static boolean isDatabaseLabel(String labelLower) {
        return labelLower.contains("database") || labelLower.contains("postgres") || labelLower.contains("mysql") || labelLower.contains("mariadb") || labelLower.contains("sql");
    }

    private static boolean isPayloadOrDataLabel(String labelLower) {
        // Keep intentionally narrow to avoid false positives on legitimate component names.
        return labelLower.contains("original url")
                || labelLower.contains("jwt")
                || labelLower.contains("html")
                || labelLower.contains("json")
                || labelLower.contains("payload");
    }

    /**
     * Architecture sanity checks to prevent “chotic” graphs:
     * - no directed cycles
     * - no cache<->database chaining
     * - no edges into Client-like nodes
     * - no obvious payload/data nodes
     * - max node count
     */
    static boolean isValidMermaidArchitecture(String mermaid) {
        if (mermaid == null || mermaid.isBlank()) return false;

        String[] lines = mermaid.strip().split("\\R");
        if (lines.length < 2) return false;

        if (!GRAPH_HEADER.matcher(lines[0].strip()).matches()) return false;

        Map<String, String> idToLabel = new java.util.HashMap<>();
        Map<String, java.util.Set<String>> adjacency = new java.util.HashMap<>();
        int edgeCount = 0;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;

            java.util.regex.Matcher m = EDGE_LINE_CAPTURE.matcher(line);
            if (!m.matches()) return false; // syntax validator should already have caught this

            String fromId = m.group(1);
            String fromLabel = m.group(2);
            String toId = m.group(3);
            String toLabel = m.group(4);

            String fromLower = fromLabel.toLowerCase();
            String toLower = toLabel.toLowerCase();

            if (isPayloadOrDataLabel(fromLower) || isPayloadOrDataLabel(toLower)) {
                return false;
            }

            if (toLower.contains("client") || toLower.contains("browser") || toLower.contains("end-user") || toLower.contains("end user")) {
                boolean fromIsClientSide = fromLower.contains("client") || fromLower.contains("browser");
                if (!fromIsClientSide) {
                    return false;
                }
            }

            if (isCacheLabel(fromLower) && isDatabaseLabel(toLower)) return false;
            if (isDatabaseLabel(fromLower) && isCacheLabel(toLower)) return false;

            // Avoid feedback loops like Worker -> Application/App
            if (fromLower.contains("worker") && (toLower.contains("application") || toLower.contains("app"))) {
                return false;
            }

            // Avoid observability pipelines
            if (fromLower.contains("analytics") && toLower.contains("logging")) return false;
            if (fromLower.contains("logging") && toLower.contains("monitoring")) return false;
            if (fromLower.contains("analytics") && toLower.contains("monitoring")) return false;

            idToLabel.putIfAbsent(fromId, fromLabel);
            idToLabel.putIfAbsent(toId, toLabel);

            adjacency.computeIfAbsent(fromId, k -> new java.util.HashSet<>()).add(toId);
            adjacency.computeIfAbsent(toId, k -> new java.util.HashSet<>());
            edgeCount++;
        }

        // Don’t be too strict on minimum; enforce an upper bound to prevent spaghetti.
        if (idToLabel.size() > 12) return false;
        if (edgeCount > 15) return false;

        // Detect directed cycles.
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Set<String> inStack = new java.util.HashSet<>();

        for (String node : adjacency.keySet()) {
            if (!visited.contains(node) && hasDirectedCycle(node, adjacency, visited, inStack)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasDirectedCycle(
            String node,
            Map<String, java.util.Set<String>> adjacency,
            java.util.Set<String> visited,
            java.util.Set<String> inStack) {
        visited.add(node);
        inStack.add(node);

        for (String next : adjacency.getOrDefault(node, java.util.Collections.emptySet())) {
            if (!visited.contains(next)) {
                if (hasDirectedCycle(next, adjacency, visited, inStack)) return true;
            } else if (inStack.contains(next)) {
                return true;
            }
        }

        inStack.remove(node);
        return false;
    }

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
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", prompt));

        String content = callAndExtract(messages);

        boolean syntaxOk = isValidMermaidSyntax(content);
        boolean architectureOk = syntaxOk && isValidMermaidArchitecture(content);

        if (!syntaxOk || !architectureOk) {
            log.warn("AI Mermaid failed {} — retrying once", !syntaxOk ? "syntax validation" : "architecture sanity checks");
            messages.add(Map.of("role", "assistant", "content", content));
            messages.add(Map.of("role", "user", "content", RETRY_USER_MESSAGE));
            content = callAndExtract(messages);

            if (!isValidMermaidSyntax(content)) {
                log.error("AI Mermaid still invalid after retry (syntax): {}",
                        content.length() > 300 ? content.substring(0, 300) : content);
                throw new RuntimeException("AI returned Mermaid that failed syntax validation");
            }

            boolean architectureOk2 = isValidMermaidArchitecture(content);
            if (!architectureOk2) {
                // Prefer returning something renderable over hard-failing the request.
                log.warn("AI Mermaid still fails architecture sanity checks; returning syntax-valid output.");
            }
        }

        log.debug("AI Mermaid output: {}", content.length() > 500 ? content.substring(0, 500) : content);
        return AiGenerateResponse.builder().mermaid(content).build();
    }

    private String callAndExtract(List<Map<String, String>> messages) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "messages", messages,
                "temperature", 0.3
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
                                    if (response.statusCode().value() == 401
                                            || b.contains("Invalid API Key")
                                            || b.contains("invalid_api_key")) {
                                        return Mono.error(new RuntimeException(
                                                "Invalid Groq API key. Get a free key at https://console.groq.com/keys "
                                                        + "and set OPENAI_API_KEY in backend/.env, "
                                                        + "or enable AI_MOCK_ENABLED=true for local dev."));
                                    }
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

            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                String errorMsg = errorNode.path("message").asText("Unknown AI error");
                log.error("OpenAI/Gemini error in response body: {}", errorMsg);
                throw new RuntimeException("AI service error: " + errorMsg);
            }

            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                int totalTokens = usage.path("total_tokens").asInt(0);
                tokenCounter.increment(totalTokens);
                log.info("OpenAI token usage: {} tokens", totalTokens);
            }

            JsonNode choices = root.path("choices");
            if (choices.isEmpty() || choices.size() == 0) {
                log.error("No choices in OpenAI response");
                throw new RuntimeException("AI service returned no choices");
            }

            JsonNode choice = choices.get(0);
            String content = null;

            JsonNode messageNode = choice.path("message");
            if (!messageNode.isMissingNode()) {
                content = messageNode.path("content").asText(null);
            }

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

            return stripMarkdownFences(content);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse AI response: " + e.getMessage());
        }
    }

    static String stripMarkdownFences(String content) {
        content = content.strip();
        if (content.startsWith("```mermaid")) content = content.substring(10);
        if (content.startsWith("```")) content = content.substring(3);
        if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
        return content.strip();
    }

    /**
     * Validates product Mermaid rules: graph LR header; each other non-empty line is ID[Label] --> ID[Label].
     */
    static boolean isValidMermaidSyntax(String mermaid) {
        if (mermaid == null || mermaid.isBlank()) {
            return false;
        }
        String[] lines = mermaid.strip().split("\\R");
        if (lines.length < 2) {
            return false;
        }
        if (!GRAPH_HEADER.matcher(lines[0].strip()).matches()) {
            return false;
        }
        boolean hasEdge = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!EDGE_LINE.matcher(line).matches()) {
                return false;
            }
            hasEdge = true;
        }
        return hasEdge;
    }
}
