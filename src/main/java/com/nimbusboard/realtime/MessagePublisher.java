package com.nimbusboard.realtime;

/**
 * Abstraction for publishing board messages.
 * Redis-backed when Redis is enabled, in-memory STOMP otherwise.
 */
public interface MessagePublisher {
    void publish(String boardId, BoardMessage message);
}
