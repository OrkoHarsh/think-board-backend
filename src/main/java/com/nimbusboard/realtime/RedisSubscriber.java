package com.nimbusboard.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class RedisSubscriber implements BoardSubscriber {

    private final RedisMessageListenerContainer listenerContainer;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, MessageListener> activeListeners = new ConcurrentHashMap<>();

    /**
     * Subscribe to a board's Redis channel and forward messages to STOMP topic.
     */
    @Override
    public void subscribeTo(String boardId) {
        if (activeListeners.containsKey(boardId)) return;

        String channel = "board:" + boardId;
        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String body = new String(message.getBody());
                BoardMessage boardMessage = objectMapper.readValue(body, BoardMessage.class);
                String destination = "/topic/board." + boardId;

                // Forward to STOMP subscribers
                messagingTemplate.convertAndSend(destination, boardMessage);
                log.debug("Forwarded Redis → STOMP {}: {}", destination, boardMessage.getType());
            } catch (Exception e) {
                log.error("Error processing Redis message for board {}", boardId, e);
            }
        };

        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
        activeListeners.put(boardId, listener);
        log.info("Subscribed to Redis channel: {}", channel);
    }

    @Override
    public void unsubscribeFrom(String boardId) {
        MessageListener listener = activeListeners.remove(boardId);
        if (listener != null) {
            listenerContainer.removeMessageListener(listener);
            log.info("Unsubscribed from Redis channel: board:{}", boardId);
        }
    }
}
