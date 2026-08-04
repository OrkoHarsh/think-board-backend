package com.nimbusboard.ai;

import com.nimbusboard.ai.dto.AiGenerateResponse;
import com.nimbusboard.auth.models.User;
import com.nimbusboard.board.BoardRepository;
import com.nimbusboard.util.ApiException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
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
    private final RedisTemplate<String, Object> redisTemplate; // nullable
    private final Map<String, Bucket> rateLimitBuckets = new ConcurrentHashMap<>();
    private final Map<String, AiGenerateResponse> localCache = new ConcurrentHashMap<>();
    private final int requestsPerMinute;
    private final boolean mockEnabled;
    private final boolean mockFallbackOnAuthError;

    public AiService(
            OpenAiClient openAiClient,
            BoardRepository boardRepository,
            @Autowired(required = false) RedisTemplate<String, Object> redisTemplate,
            @Value("${app.rate-limit.ai-requests-per-minute}") int requestsPerMinute,
            @Value("${app.ai.mock-enabled:false}") boolean mockEnabled,
            @Value("${app.ai.mock-fallback-on-auth-error:true}") boolean mockFallbackOnAuthError) {
        this.openAiClient = openAiClient;
        this.boardRepository = boardRepository;
        this.redisTemplate = redisTemplate;
        this.requestsPerMinute = requestsPerMinute;
        this.mockEnabled = mockEnabled;
        this.mockFallbackOnAuthError = mockFallbackOnAuthError;
    }

    public AiGenerateResponse generate(String boardId, String prompt, User user) {
        if (boardId == null || boardId.isBlank()) {
            throw new ApiException("Board ID is required", HttpStatus.BAD_REQUEST);
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

        // Sanitize prompt
        String sanitized = sanitizePrompt(prompt);
        if (sanitized.isBlank()) {
            throw new ApiException("Prompt cannot be empty after sanitization", HttpStatus.BAD_REQUEST);
        }

        // Check cache (Redis if available, else local)
        String cacheKey = "ai:generate:" + boardId + ":" + sanitized.hashCode();
        AiGenerateResponse cached = getFromCache(cacheKey);
        if (cached != null) {
            log.info("AI cache hit for board {} by user {}", boardId, user.getEmail());
            return cached;
        }

        // Call OpenAI
        log.info("AI generation request: board={}, user={}, prompt_length={}",
                boardId, user.getEmail(), sanitized.length());

        AiGenerateResponse response;
        try {
            if (mockEnabled) {
                log.info("AI mock mode enabled — returning sample diagram for board {}", boardId);
                response = buildMockResponse(sanitized);
            } else {
                response = openAiClient.generate(sanitized);
            }
        } catch (RuntimeException e) {
            if (mockFallbackOnAuthError && isInvalidApiKeyError(e)) {
                log.warn("AI API key rejected — falling back to sample diagram for board {}: {}",
                        boardId, e.getMessage());
                response = buildMockResponse(sanitized);
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

        // Cache result for 5 minutes
        putInCache(cacheKey, response);

        return response;
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

    private String sanitizePrompt(String prompt) {
        if (prompt == null) return "";
        // Remove potential injection patterns, limit length
        String sanitized = prompt.replaceAll("[<>{}]", "").trim();
        if (sanitized.length() > 2000) {
            sanitized = sanitized.substring(0, 2000);
        }
        return sanitized;
    }

    private boolean isInvalidApiKeyError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("invalid_api_key")
                || msg.contains("Invalid API Key")
                || msg.contains("Invalid Groq API key");
    }

    private AiGenerateResponse buildMockResponse(String prompt) {
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

        return AiGenerateResponse.builder().mermaid(mermaid.strip()).build();
    }
}
