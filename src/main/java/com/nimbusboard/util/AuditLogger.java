package com.nimbusboard.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Centralized audit logger for security-sensitive operations.
 */
@Slf4j
@Component
public class AuditLogger {

    public void logBoardDelete(String boardId, String userId) {
        log.info("AUDIT | action=BOARD_DELETE | boardId={} | userId={}", boardId, userId);
    }

    public void logPermissionChange(String boardId, String userId, String change) {
        log.info("AUDIT | action=PERMISSION_CHANGE | boardId={} | userId={} | change={}",
                boardId, userId, change);
    }

    public void logAiGeneration(String boardId, String userId, int promptLength) {
        log.info("AUDIT | action=AI_GENERATE | boardId={} | userId={} | promptLength={}",
                boardId, userId, promptLength);
    }

    public void logLogin(String email, boolean success) {
        log.info("AUDIT | action=LOGIN | email={} | success={}", email, success);
    }
}
