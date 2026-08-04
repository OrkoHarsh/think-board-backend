package com.nimbusboard.realtime;

/**
 * Abstraction for subscribing to board channels.
 * Redis-backed when Redis is enabled, no-op otherwise.
 */
public interface BoardSubscriber {
    void subscribeTo(String boardId);
    void unsubscribeFrom(String boardId);
}
