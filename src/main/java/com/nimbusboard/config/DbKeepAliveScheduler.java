package com.nimbusboard.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pings Postgres on a fixed interval so Railway idle TCP drops do not leave
 * the pool with dead connections that stall the first user request.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DbKeepAliveScheduler {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(fixedDelayString = "${app.keepalive.db-interval-ms:120000}")
    public void pingDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.debug("DB keep-alive ping ok");
        } catch (Exception e) {
            log.warn("DB keep-alive ping failed: {}", e.getMessage());
        }
    }
}
