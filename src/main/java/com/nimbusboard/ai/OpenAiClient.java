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

    private static final String FLOWCHART_SYSTEM_PROMPT = """
            You are a flowchart expert. Given a user prompt, generate a clear Mermaid flowchart of the process.

            SYNTAX RULES (mandatory):
            1. Start with "flowchart TD" (top down).
            2. Steps use square brackets: ID[Label]
            3. Decisions use curly braces: ID{Question?}
            4. Start and end use stadium shape: ID([Start]) and ID([End])
            5. Edges are: ID --> ID   or with a label: ID -->|Yes| ID
            6. Every edge leaving a decision MUST have a label (Yes/No or the branch name).
            7. Node IDs are short alphanumeric tokens (A, B, C, S1, D1). Define a label the first time an ID appears; reuse the bare ID afterwards.
            8. Output ONLY the Mermaid flowchart — no markdown fences, no commentary.

            FLOW RULES (mandatory):
            1. Exactly one start node and at least one end node.
            2. Keep 6-16 nodes. Every node must be reachable from the start.
            3. Decisions have at least two outgoing labelled branches.
            4. Labels are short imperative steps ("Validate input", "Send email"), not sentences.
            5. Loops back to an earlier step are allowed when the process genuinely repeats.

            EXAMPLE:

            Prompt: user login flow
            flowchart TD
            A([Start]) --> B[Open login page]
            B --> C[Enter credentials]
            C --> D{Credentials valid?}
            D -->|No| E[Show error]
            E --> C
            D -->|Yes| F{MFA enabled?}
            F -->|Yes| G[Prompt for MFA code]
            G --> H[Verify code]
            H --> I[Create session]
            F -->|No| I[Create session]
            I --> J([End])
            """;

    private static final String FLOWCHART_RETRY_MESSAGE = """
            Previous output broke the flowchart rules. Return ONLY a valid Mermaid flowchart.
            Must start with flowchart TD. Steps ID[Label], decisions ID{Question?}, terminals ID([Start]) / ID([End]).
            Edges must be ID --> ID or ID -->|Label| ID. No markdown fences, no commentary.
            Keep 6-16 nodes, one start node, and label every branch out of a decision.
            """;

    private static final String CLASS_SYSTEM_PROMPT = """
            You are a UML class diagram expert. Given a user prompt, generate a Mermaid class diagram of the domain model.

            SYNTAX RULES (mandatory):
            1. Start with "classDiagram".
            2. Declare every class as a block:
               class ClassName {
               +Type attributeName
               +methodName() ReturnType
               }
            3. Visibility prefixes: + public, - private, # protected. Every member needs one.
            4. Attributes are "+Type name". Methods always end with "()" followed by the return type.
            5. Relationships go after the class blocks, one per line, using only:
               A --> B      (association)
               A <|-- B     (B inherits A)
               A *-- B      (composition)
               A o-- B      (aggregation)
            6. Class names are PascalCase with no spaces. Output ONLY the Mermaid class diagram — no markdown fences, no commentary.

            MODELLING RULES (mandatory):
            1. Produce 3-8 classes covering the user's domain.
            2. Each class needs at least one attribute; add methods only where they carry meaning.
            3. Use real domain types (UUID, String, int, Money, LocalDate, List~Order~), not vague ones.
            4. Every class must participate in at least one relationship.
            5. Model the user's domain, not a generic template.

            EXAMPLE:

            Prompt: class diagram for an e-commerce order system
            classDiagram
            class Customer {
            +UUID id
            +String name
            +String email
            +placeOrder() Order
            }
            class Order {
            +UUID id
            +LocalDate createdAt
            +String status
            +total() Money
            }
            class OrderLine {
            +int quantity
            +Money unitPrice
            }
            class Product {
            +UUID id
            +String title
            +Money price
            }
            Customer --> Order
            Order *-- OrderLine
            Product <-- OrderLine
            """;

    private static final String CLASS_RETRY_MESSAGE = """
            Previous output broke the class diagram rules. Return ONLY a valid Mermaid class diagram.
            Must start with classDiagram. Every class is a "class Name {" block closed by "}".
            Every member line starts with +, - or #. Methods end with "()" then the return type.
            Relationships only: A --> B, A <|-- B, A *-- B, A o-- B.
            3-8 classes, no markdown fences, no commentary.
            """;

    /** Header line: graph LR (optional trailing whitespace). */
    private static final Pattern GRAPH_HEADER = Pattern.compile("^graph\\s+LR\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern FLOWCHART_HEADER =
            Pattern.compile("^(flowchart|graph)\\s+(TD|TB|LR)\\s*$", Pattern.CASE_INSENSITIVE);

    /** A node reference: bare ID, ID[Step], ID{Decision?} or ID([Terminal]). Inner groups are
     *  non-capturing so the edge pattern's own groups stay at 1 and 2. */
    private static final String NODE_REF =
            "[A-Za-z][A-Za-z0-9_]{0,7}(?:\\[[^\\[\\]{}()|]+]|\\{[^{}|]+}|\\(\\[[^\\[\\]()|]+]\\))?";

    private static final Pattern FLOWCHART_EDGE = Pattern.compile(
            "^(" + NODE_REF + ")\\s*-->\\s*(?:\\|[^|]+\\|\\s*)?(" + NODE_REF + ")$");

    private static final Pattern FLOWCHART_NODE_ID = Pattern.compile("^([A-Za-z][A-Za-z0-9_]{0,7})");

    private static final Pattern CLASS_DECLARATION =
            Pattern.compile("^class\\s+([A-Za-z][A-Za-z0-9_]*)\\s*\\{$");

    private static final Pattern CLASS_MEMBER =
            Pattern.compile("^[+\\-#~][^{}]+$");

    private static final Pattern CLASS_RELATION = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*\\s*(-->|<--|<\\|--|--\\|>|\\*--|--\\*|o--|--o|\\.\\.>|<\\.\\.|--)\\s*"
                    + "[A-Za-z][A-Za-z0-9_]*(\\s*:\\s*[^{}]+)?$");

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

    /**
     * Flowchart rules: a flowchart/graph header, then edge lines only. Node labels may be declared
     * inline on first use. Bounded so the board never receives spaghetti.
     */
    static boolean isValidFlowchart(String mermaid) {
        if (mermaid == null || mermaid.isBlank()) return false;

        String[] lines = mermaid.strip().split("\\R");
        if (lines.length < 2) return false;
        if (!FLOWCHART_HEADER.matcher(lines[0].strip()).matches()) return false;

        java.util.Set<String> nodes = new java.util.HashSet<>();
        java.util.Set<String> targets = new java.util.HashSet<>();
        java.util.Set<String> sources = new java.util.HashSet<>();
        int edgeCount = 0;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;

            java.util.regex.Matcher m = FLOWCHART_EDGE.matcher(line);
            if (!m.matches()) return false;

            String fromId = nodeId(m.group(1));
            String toId = nodeId(m.group(2));
            if (fromId == null || toId == null) return false;
            if (fromId.equals(toId)) return false; // self-loop renders as an unreadable stub

            nodes.add(fromId);
            nodes.add(toId);
            sources.add(fromId);
            targets.add(toId);
            edgeCount++;
        }

        if (edgeCount == 0) return false;
        if (nodes.size() > 18 || edgeCount > 24) return false;

        // Exactly one entry point keeps the flow readable and gives the layout a root.
        java.util.Set<String> roots = new java.util.HashSet<>(sources);
        roots.removeAll(targets);
        if (roots.size() != 1) return false;

        // At least one terminal node.
        java.util.Set<String> leaves = new java.util.HashSet<>(targets);
        leaves.removeAll(sources);
        return !leaves.isEmpty();
    }

    private static String nodeId(String nodeRef) {
        if (nodeRef == null) return null;
        java.util.regex.Matcher m = FLOWCHART_NODE_ID.matcher(nodeRef.strip());
        return m.find() ? m.group(1) : null;
    }

    /**
     * Class diagram rules: classDiagram header, balanced class blocks with prefixed members, and
     * relationship lines referring only to declared classes.
     */
    static boolean isValidClassDiagram(String mermaid) {
        if (mermaid == null || mermaid.isBlank()) return false;

        String[] lines = mermaid.strip().split("\\R");
        if (lines.length < 2) return false;
        if (!lines[0].strip().equalsIgnoreCase("classDiagram")) return false;

        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        java.util.List<String[]> relations = new java.util.ArrayList<>();
        String openClass = null;
        int membersInClass = 0;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;

            if (openClass != null) {
                if (line.equals("}")) {
                    if (membersInClass == 0) return false; // empty compartments are useless
                    openClass = null;
                    membersInClass = 0;
                    continue;
                }
                if (!CLASS_MEMBER.matcher(line).matches()) return false;
                membersInClass++;
                continue;
            }

            java.util.regex.Matcher decl = CLASS_DECLARATION.matcher(line);
            if (decl.matches()) {
                String name = decl.group(1);
                if (!declared.add(name)) return false; // duplicate class block
                openClass = name;
                membersInClass = 0;
                continue;
            }

            if (CLASS_RELATION.matcher(line).matches()) {
                String[] parts = line.split("\\s*(-->|<--|<\\|--|--\\|>|\\*--|--\\*|o--|--o|\\.\\.>|<\\.\\.|--)\\s*", 2);
                if (parts.length != 2) return false;
                String right = parts[1].split(":")[0].strip();
                relations.add(new String[] { parts[0].strip(), right });
                continue;
            }

            return false;
        }

        if (openClass != null) return false; // unbalanced block
        if (declared.isEmpty() || declared.size() > 12) return false;

        for (String[] relation : relations) {
            if (!declared.contains(relation[0]) || !declared.contains(relation[1])) return false;
        }
        return true;
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
        return generate(prompt, DiagramType.HLD);
    }

    public AiGenerateResponse generate(String prompt, DiagramType type) {
        if (type == null || type == DiagramType.HLD) {
            return generateHld(prompt);
        }
        return generateTyped(prompt, type);
    }

    /**
     * Non-HLD diagrams: same call/validate/retry shape as the architecture path, but with the
     * prompt and validator for the requested type.
     */
    private AiGenerateResponse generateTyped(String prompt, DiagramType type) {
        String systemPrompt = type == DiagramType.FLOWCHART ? FLOWCHART_SYSTEM_PROMPT : CLASS_SYSTEM_PROMPT;
        String retryMessage = type == DiagramType.FLOWCHART ? FLOWCHART_RETRY_MESSAGE : CLASS_RETRY_MESSAGE;

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", prompt));

        String content = callAndExtract(messages);

        if (!isValidForType(content, type)) {
            log.warn("AI {} output failed validation — retrying once", type);
            messages.add(Map.of("role", "assistant", "content", content));
            messages.add(Map.of("role", "user", "content", retryMessage));
            content = callAndExtract(messages);

            if (!isValidForType(content, type)) {
                log.error("AI {} output still invalid after retry: {}", type,
                        content.length() > 300 ? content.substring(0, 300) : content);
                throw new RuntimeException("AI returned a " + type.label() + " that failed validation");
            }
        }

        log.debug("AI {} output: {}", type, content.length() > 500 ? content.substring(0, 500) : content);
        return AiGenerateResponse.builder().mermaid(content).diagramType(type.name()).build();
    }

    static boolean isValidForType(String mermaid, DiagramType type) {
        return switch (type) {
            case HLD -> isValidMermaidSyntax(mermaid) && isValidMermaidArchitecture(mermaid);
            case FLOWCHART -> isValidFlowchart(mermaid);
            case CLASS -> isValidClassDiagram(mermaid);
        };
    }

    private AiGenerateResponse generateHld(String prompt) {
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
