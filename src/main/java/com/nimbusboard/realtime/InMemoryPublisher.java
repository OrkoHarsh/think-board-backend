package com.nimbusboard.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * In-memory publisher that forwards messages directly to STOMP subscribers.
 * Used when Redis is not available (single-instance mode).
 */
@Slf4j
@Component
@ConditionalOnMissingBean(RedisPublisher.class)
@RequiredArgsConstructor
public class InMemoryPublisher implements MessagePublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void publish(String boardId, BoardMessage message) {
        String destination = "/topic/board." + boardId;
        messagingTemplate.convertAndSend(destination, message);
        log.debug("Published to STOMP {}: {}", destination, message.getType());
    }
}
