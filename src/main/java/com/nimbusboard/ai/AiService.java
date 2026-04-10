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

    public AiService(
            OpenAiClient openAiClient,
            BoardRepository boardRepository,
            @Autowired(required = false) RedisTemplate<String, Object> redisTemplate,
            @Value("${app.rate-limit.ai-requests-per-minute}") int requestsPerMinute) {
        this.openAiClient = openAiClient;
        this.boardRepository = boardRepository;
        this.redisTemplate = redisTemplate;
        this.requestsPerMinute = requestsPerMinute;
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
            response = openAiClient.generate(sanitized);
        } catch (RuntimeException e) {
            log.error("AI generation failed for board {} by user {}: {}",
                    boardId, user.getEmail(), e.getMessage());
            throw new ApiException("AI generation failed: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
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
}
