package com.nimbusboard.realtime;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.auth.models.UserRepository;
import com.nimbusboard.board.BoardObjectRepository;
import com.nimbusboard.board.BoardService;
import com.nimbusboard.board.models.BoardObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeService {

    private final BoardService boardService;
    private final BoardObjectRepository boardObjectRepository;
    private final MessagePublisher messagePublisher;
    private final BoardSubscriber boardSubscriber;
    private final UserRepository userRepository;

    /**
     * Safely parse a userId string to UUID, returning null if invalid.
     */
    private UUID safeParseUserId(String userId) {
        if (userId == null || "anonymous".equals(userId)) return null;
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid userId format: {}", userId);
            return null;
        }
    }

    /**
     * Handle object creation from WebSocket.
     */
    public void handleObjectCreate(String boardId, Map<String, Object> payload, String userId) {
        String objectId = (String) payload.get("id");
        String type = (String) payload.get("type");

        // Extract all properties except id, type, boardId
        Map<String, Object> properties = new HashMap<>(payload);
        properties.remove("id");
        properties.remove("type");
        properties.remove("boardId");

        boardService.createObject(UUID.fromString(boardId), objectId, type, properties,
                safeParseUserId(userId));

        BoardMessage msg = BoardMessage.builder()
                .type("add_object")
                .boardId(boardId)
                .objectId(objectId)
                .userId(userId)
                .payload(payload)
                .build();

        messagePublisher.publish(boardId, msg);
        log.debug("Object created: {} on board {}", objectId, boardId);
    }

    /**
     * Handle object update from WebSocket.
     */
    public void handleObjectUpdate(String boardId, Map<String, Object> payload, String userId) {
        String objectId = (String) payload.get("objectId");
        @SuppressWarnings("unchecked")
        Map<String, Object> updates = (Map<String, Object>) payload.get("updates");

        if (objectId != null && updates != null) {
            try {
                BoardObject existing = boardObjectRepository.findById(objectId).orElse(null);
                if (existing != null) {
                    existing.getProperties().putAll(updates);
                    boardObjectRepository.save(existing);
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                // Another update beat us to it — re-fetch the latest and apply on top (last-write-wins)
                log.debug("Version conflict on object {}, retrying with latest version", objectId);
                BoardObject fresh = boardObjectRepository.findById(objectId).orElse(null);
                if (fresh != null) {
                    fresh.getProperties().putAll(updates);
                    boardObjectRepository.save(fresh);
                }
            }
        }

        BoardMessage msg = BoardMessage.builder()
                .type("update_object")
                .boardId(boardId)
                .objectId(objectId)
                .userId(userId)
                .payload(payload)
                .build();

        messagePublisher.publish(boardId, msg);
    }

    /**
     * Handle object deletion from WebSocket.
     */
    public void handleObjectDelete(String boardId, Map<String, Object> payload, String userId) {
        String objectId = (String) payload.get("objectId");

        if (objectId != null) {
            boardObjectRepository.deleteById(objectId);
        }

        BoardMessage msg = BoardMessage.builder()
                .type("delete_object")
                .boardId(boardId)
                .objectId(objectId)
                .userId(userId)
                .payload(payload)
                .build();

        messagePublisher.publish(boardId, msg);
        log.debug("Object deleted: {} from board {}", objectId, boardId);
    }

    /**
     * Handle cursor movement (ephemeral — not persisted).
     */
    public void handleCursorMove(String boardId, Map<String, Object> payload, String userId) {
        // Add userId to payload so frontend knows whose cursor it is
        payload.put("userId", userId);

        BoardMessage msg = BoardMessage.builder()
                .type("cursor_move")
                .boardId(boardId)
                .userId(userId)
                .payload(payload)
                .build();

        messagePublisher.publish(boardId, msg);
    }

    /**
     * Handle user join/leave events.
     */
    public void handleUserJoin(String boardId, String userId) {
        boardSubscriber.subscribeTo(boardId);

        // Fetch user name from database to broadcast with join event
        String userName = "User";
        try {
            UUID userUUID = UUID.fromString(userId);
            User user = userRepository.findById(userUUID).orElse(null);
            if (user != null && user.getName() != null) {
                userName = user.getName();
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid userId format: {}", userId);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("name", userName);

        BoardMessage msg = BoardMessage.builder()
                .type("user.join")
                .boardId(boardId)
                .userId(userId)
                .payload(payload)
                .build();

        messagePublisher.publish(boardId, msg);
        log.info("User {} ({}) joined board {}", userId, userName, boardId);
    }

    public void handleUserLeave(String boardId, String userId) {
        BoardMessage msg = BoardMessage.builder()
                .type("user.leave")
                .boardId(boardId)
                .userId(userId)
                .payload(Map.of("userId", userId))
                .build();

        messagePublisher.publish(boardId, msg);
        log.info("User {} left board {}", userId, boardId);
    }
}
