package com.nimbusboard.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * No-op subscriber used when Redis is not available.
 * In single-instance mode, STOMP's simple broker handles subscriptions directly.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(RedisSubscriber.class)
public class InMemorySubscriber implements BoardSubscriber {

    @Override
    public void subscribeTo(String boardId) {
        log.debug("In-memory mode: STOMP broker handles board {} subscriptions directly", boardId);
    }

    @Override
    public void unsubscribeFrom(String boardId) {
        log.debug("In-memory mode: No Redis channel to unsubscribe for board {}", boardId);
    }
}
