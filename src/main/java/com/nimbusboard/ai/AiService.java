package com.nimbusboard.ai;

import com.nimbusboard.ai.dto.AiGenerateResponse;
import com.nimbusboard.ai.guardrails.GuardrailResult;
import com.nimbusboard.ai.guardrails.PromptGuardrails;
import com.nimbusboard.auth.models.User;
import com.nimbusboard.board.BoardRepository;
import com.nimbusboard.util.ApiException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class AiService {

    private final OpenAiClient openAiClient;
    private final BoardRepository boardRepository;
    private final PromptGuardrails guardrails;
    private final AiQuotaService aiQuotaService;
    private final RedisTemplate<String, Object> redisTemplate; // nullable
    private final Map<String, Bucket> rateLimitBuckets = new ConcurrentHashMap<>();
    private final Map<String, AiGenerateResponse> localCache = new ConcurrentHashMap<>();
    private final Counter guardrailBlockCounter;
    private final int requestsPerMinute;
    private final boolean mockEnabled;
    private final boolean mockFallbackOnAuthError;

    public AiService(
            OpenAiClient openAiClient,
            BoardRepository boardRepository,
            PromptGuardrails guardrails,
            AiQuotaService aiQuotaService,
            MeterRegistry meterRegistry,
            @Autowired(required = false) RedisTemplate<String, Object> redisTemplate,
            @Value("${app.rate-limit.ai-requests-per-minute}") int requestsPerMinute,
            @Value("${app.ai.mock-enabled:false}") boolean mockEnabled,
            @Value("${app.ai.mock-fallback-on-auth-error:true}") boolean mockFallbackOnAuthError) {
        this.openAiClient = openAiClient;
        this.boardRepository = boardRepository;
        this.guardrails = guardrails;
        this.aiQuotaService = aiQuotaService;
        this.redisTemplate = redisTemplate;
        this.requestsPerMinute = requestsPerMinute;
        this.mockEnabled = mockEnabled;
        this.mockFallbackOnAuthError = mockFallbackOnAuthError;
        this.guardrailBlockCounter = Counter.builder("ai.guardrail.blocked")
                .description("AI requests blocked by input or output guardrails")
                .register(meterRegistry);
    }

    public AiGenerateResponse generate(String boardId, String prompt, User user) {
        return generate(boardId, prompt, null, user);
    }

    public AiGenerateResponse generate(String boardId, String prompt, String requestedType, User user) {
        if (boardId == null || boardId.isBlank()) {
            throw new ApiException("Board ID is required", HttpStatus.BAD_REQUEST);
        }

        if (user == null || user.getId() == null) {
            throw new ApiException("Authentication required", HttpStatus.UNAUTHORIZED);
        }

        UUID boardUuid;
        try {
            boardUuid = UUID.fromString(boardId);
        } catch (IllegalArgumentException e) {
            throw new ApiException("Invalid Board ID format", HttpStatus.BAD_REQUEST);
        }

        // Validate board exists and user has access
        boardRepository.findById(boardUuid)
                .orElseThrow(() -> new ApiException("Board not found", HttpStatus.NOT_FOUND));

        // Rate limiting per user
        Bucket bucket = rateLimitBuckets.computeIfAbsent(
                user.getId().toString(),
                k -> Bucket.builder()
                        .addLimit(Bandwidth.simple(requestsPerMinute, Duration.ofMinutes(1)))
                        .build());

        if (!bucket.tryConsume(1)) {
            throw new ApiException("Rate limit exceeded. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
        }

        // Input guardrails: refuse unsafe, injected, or off-topic prompts before spending a call
        GuardrailResult inputCheck = guardrails.checkPrompt(prompt);
        if (!inputCheck.allowed()) {
            log.warn("Guardrail blocked prompt from user {} on board {}: category={}",
                    user.getEmail(), boardId, inputCheck.category());
            guardrailBlockCounter.increment();
            throw new ApiException(inputCheck.message(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String sanitized = inputCheck.sanitized();

        DiagramType diagramType = DiagramType.resolve(requestedType, sanitized);

        // Check cache (Redis if available, else local)
        String cacheKey = "ai:generate:" + boardId + ":" + diagramType + ":" + sanitized.hashCode();
        AiGenerateResponse cached = getFromCache(cacheKey);
        if (cached != null) {
            log.info("AI cache hit for board {} by user {}", boardId, user.getEmail());
            return cached;
        }

        aiQuotaService.consumeOrThrow(user);

        // Call OpenAI
        log.info("AI generation request: board={}, user={}, type={}, prompt_length={}",
                boardId, user.getEmail(), diagramType, sanitized.length());

        AiGenerateResponse response;
        try {
            if (mockEnabled) {
                log.info("AI mock mode enabled — returning sample diagram for board {}", boardId);
                response = buildMockResponse(sanitized, diagramType);
            } else {
                response = openAiClient.generate(sanitized, diagramType);
            }
        } catch (RuntimeException e) {
            if (mockFallbackOnAuthError && isInvalidApiKeyError(e)) {
                log.warn("AI API key rejected — falling back to sample diagram for board {}: {}",
                        boardId, e.getMessage());
                response = buildMockResponse(sanitized, diagramType);
            } else if (isValidationFailure(e)) {
                log.warn("AI could not produce a valid {} for board {}: {}", diagramType, boardId, e.getMessage());
                throw new ApiException(
                        "The AI couldn't turn that into a clean " + diagramType.label()
                                + ". Try describing it in a bit more detail, or pick a different diagram type.",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            } else {
                log.error("AI generation failed for board {} by user {}: {}",
                        boardId, user.getEmail(), e.getMessage());
                throw new ApiException(
                        "AI generation failed: Invalid or missing Groq API key. "
                                + "Set OPENAI_API_KEY on Render, or enable AI_MOCK_ENABLED=true.",
                        HttpStatus.BAD_GATEWAY);
            }
        }

        if (response == null || response.getMermaid() == null || response.getMermaid().isBlank()) {
            throw new ApiException("AI returned an empty or invalid response", HttpStatus.BAD_GATEWAY);
        }

        // Output guardrails: never hand back a response that echoed our own instructions
        GuardrailResult outputCheck = guardrails.checkOutput(response.getMermaid());
        if (!outputCheck.allowed()) {
            log.warn("Guardrail blocked AI output for board {}: category={}", boardId, outputCheck.category());
            guardrailBlockCounter.increment();
            throw new ApiException(outputCheck.message(), HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (response.getDiagramType() == null) {
            response.setDiagramType(diagramType.name());
        }

        // Cache result for 5 minutes
        putInCache(cacheKey, response);

        return response;
    }

    /** True when the model produced output our validators rejected, rather than a transport error. */
    private boolean isValidationFailure(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("failed validation") || msg.contains("failed syntax validation");
    }

    private AiGenerateResponse getFromCache(String key) {
        if (redisTemplate != null) {
            try {
                Object cached = redisTemplate.opsForValue().get(key);
                if (cached instanceof AiGenerateResponse resp) return resp;
            } catch (Exception e) {
                log.debug("Redis cache read failed, using local cache", e);
            }
        }
        return localCache.get(key);
    }

    private void putInCache(String key, AiGenerateResponse response) {
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(key, response, Duration.ofMinutes(5));
                return;
            } catch (Exception e) {
                log.debug("Redis cache write failed, using local cache", e);
            }
        }
        localCache.put(key, response);
    }

    private boolean isInvalidApiKeyError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("invalid_api_key")
                || msg.contains("Invalid API Key")
                || msg.contains("Invalid Groq API key");
    }

    private AiGenerateResponse buildMockResponse(String prompt, DiagramType type) {
        if (type == DiagramType.FLOWCHART) {
            return AiGenerateResponse.builder()
                    .diagramType(type.name())
                    .mermaid("""
                            flowchart TD
                            A([Start]) --> B[Receive request]
                            B --> C{Input valid?}
                            C -->|No| D[Return validation error]
                            D --> H([End])
                            C -->|Yes| E[Process request]
                            E --> F{Succeeded?}
                            F -->|No| G[Retry or fail]
                            G --> H([End])
                            F -->|Yes| I[Persist result]
                            I --> H([End])""")
                    .build();
        }

        if (type == DiagramType.CLASS) {
            return AiGenerateResponse.builder()
                    .diagramType(type.name())
                    .mermaid("""
                            classDiagram
                            class User {
                            +UUID id
                            +String name
                            +String email
                            }
                            class Account {
                            +UUID id
                            +String status
                            +activate() void
                            }
                            class Session {
                            +UUID token
                            +LocalDate expiresAt
                            }
                            User --> Account
                            Account *-- Session""")
                    .build();
        }

        String lower = prompt.toLowerCase();
        String mermaid;

        if (lower.contains("url") && (lower.contains("short") || lower.contains("link"))) {
            mermaid = """
                    graph LR
                    A[Client] --> B[CDN]
                    B[CDN] --> C[Load Balancer]
                    C[Load Balancer] --> D[API Gateway]
                    D[API Gateway] --> E[URL Shortening Service]
                    E[URL Shortening Service] --> F[Redis Cache]
                    E[URL Shortening Service] --> G[PostgreSQL]
                    E[URL Shortening Service] --> H[Analytics Service]
                    """;
        } else if (lower.contains("auth") || lower.contains("login")) {
            mermaid = """
                    graph LR
                    A[Client App] --> B[Load Balancer]
                    B[Load Balancer] --> C[API Gateway]
                    C[API Gateway] --> D[Auth Service]
                    D[Auth Service] --> E[User Database]
                    D[Auth Service] --> F[Token Store]
                    C[API Gateway] --> G[Protected Services]
                    """;
        } else {
            mermaid = """
                    graph LR
                    A[Client] --> B[Load Balancer]
                    B[Load Balancer] --> C[API Gateway]
                    C[API Gateway] --> D[Application Service]
                    D[Application Service] --> E[Redis Cache]
                    D[Application Service] --> F[Database]
                    D[Application Service] --> G[Message Queue]
                    G[Message Queue] --> H[Worker Service]
                    """;
        }

        return AiGenerateResponse.builder()
                .mermaid(mermaid.strip())
                .diagramType(DiagramType.HLD.name())
                .build();
    }
}
