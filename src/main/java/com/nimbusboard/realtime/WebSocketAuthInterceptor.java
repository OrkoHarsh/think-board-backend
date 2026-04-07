package com.nimbusboard.realtime;

import com.nimbusboard.auth.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Intercepts STOMP CONNECT frames to authenticate via JWT token.
 * The token can be sent as a "token" native header or in the query string
 * (already parsed by the JwtFilter during the HTTP upgrade).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtProvider jwtProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            // Try to get token from STOMP native header
            List<String> tokenHeaders = accessor.getNativeHeader("token");
            String token = (tokenHeaders != null && !tokenHeaders.isEmpty()) ? tokenHeaders.get(0) : null;

            if (token != null && jwtProvider.validateToken(token)) {
                UUID userId = jwtProvider.getUserIdFromToken(token);
                accessor.setUser(new StompPrincipal(userId.toString()));
                log.debug("WebSocket authenticated: userId={}", userId);
            } else {
                log.debug("WebSocket connection without valid token — user will be anonymous");
            }
        }

        return message;
    }

    /**
     * Simple Principal wrapping the user ID string.
     */
    public record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
