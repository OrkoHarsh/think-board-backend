package com.nimbusboard.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class RedisPublisher implements MessagePublisher {

    private static final String CHANNEL_PREFIX = "board:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(String boardId, BoardMessage message) {
        try {
            String channel = CHANNEL_PREFIX + boardId;
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(channel, json);
            log.debug("Published to Redis channel {}: {}", channel, message.getType());
        } catch (Exception e) {
            log.error("Failed to publish to Redis", e);
        }
    }
}
