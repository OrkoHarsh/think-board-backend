package com.nimbusboard.realtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Unified message format for WebSocket communication.
 * Matches the frontend's { type, payload } contract.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BoardMessage {
    private String type;     // object.create, object.update, object.delete, cursor.move, user.join, user.leave
    private String boardId;
    private String objectId;
    private String userId;
    private Map<String, Object> payload;
}
