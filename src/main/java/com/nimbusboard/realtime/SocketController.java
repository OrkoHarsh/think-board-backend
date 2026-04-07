package com.nimbusboard.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class SocketController {

    private final RealtimeService realtimeService;

    /**
     * Frontend sends to: /app/board.{boardId}
     * Message format: { "type": "add_object|update_object|delete_object|cursor_move", "payload": {...} }
     */
    @MessageMapping("/board.{boardId}")
    public void handleBoardMessage(
            @DestinationVariable String boardId,
            @Payload Map<String, Object> message,
            SimpMessageHeaderAccessor headerAccessor) {

        String userId = getUserId(headerAccessor);
        String type = (String) message.get("type");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.get("payload");

        if (payload == null) {
            log.warn("Received message with null payload for board {}", boardId);
            return;
        }

        log.debug("WS message: type={}, boardId={}, user={}", type, boardId, userId);

        switch (type) {
            case "add_object" -> realtimeService.handleObjectCreate(boardId, payload, userId);
            case "update_object" -> realtimeService.handleObjectUpdate(boardId, payload, userId);
            case "delete_object" -> realtimeService.handleObjectDelete(boardId, payload, userId);
            case "cursor_move" -> realtimeService.handleCursorMove(boardId, payload, userId);
            case "user_join", "user.join" -> realtimeService.handleUserJoin(boardId, userId);
            case "user_leave", "user.leave" -> realtimeService.handleUserLeave(boardId, userId);
            default -> log.warn("Unknown message type: {}", type);
        }
    }

    private String getUserId(SimpMessageHeaderAccessor headerAccessor) {
        Principal principal = headerAccessor.getUser();
        return principal != null ? principal.getName() : "anonymous";
    }
}
