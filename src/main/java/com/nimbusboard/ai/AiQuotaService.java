package com.nimbusboard.ai;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.util.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Durable per-user daily AI quota, stored in Postgres.
 * Unlimited emails come from config only — never from the client.
 */
@Slf4j
@Service
public class AiQuotaService {

    static final String DAILY_LIMIT_MESSAGE = "Daily AI limit reached. Try again tomorrow.";

    private static final String CONSUME_SQL = """
            INSERT INTO ai_usage_daily (user_id, usage_date, call_count)
            VALUES (?, (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date, 1)
            ON CONFLICT (user_id, usage_date)
            DO UPDATE SET
                call_count = ai_usage_daily.call_count + 1,
                updated_at = now()
            WHERE ai_usage_daily.call_count < ?
            RETURNING call_count
            """;

    private final JdbcTemplate jdbcTemplate;
    private final int dailyLimit;
    private final Set<String> unlimitedEmails;

    public AiQuotaService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.rate-limit.ai-requests-per-day:3}") int dailyLimit,
            @Value("${app.rate-limit.unlimited-emails:}") String unlimitedEmailsRaw) {
        this.jdbcTemplate = jdbcTemplate;
        this.dailyLimit = dailyLimit;
        this.unlimitedEmails = parseEmails(unlimitedEmailsRaw);
    }

    /**
     * Atomically consumes one daily slot. No-op for allowlisted emails.
     * Fail-closed: DB errors become 503, never a skipped Groq call.
     */
    public void consumeOrThrow(User user) {
        if (user == null || user.getId() == null) {
            throw new ApiException("Authentication required", HttpStatus.UNAUTHORIZED);
        }
        if (isUnlimited(user)) {
            log.debug("AI daily quota skipped for unlimited user {}", user.getId());
            return;
        }

        try {
            var counts = jdbcTemplate.query(
                    CONSUME_SQL,
                    (rs, rowNum) -> rs.getInt(1),
                    user.getId(),
                    dailyLimit);
            if (counts.isEmpty()) {
                log.info("AI daily quota exhausted for user {}", user.getId());
                throw new ApiException(DAILY_LIMIT_MESSAGE, HttpStatus.TOO_MANY_REQUESTS);
            }
        } catch (ApiException e) {
            throw e;
        } catch (DataAccessException e) {
            log.error("AI quota check failed for user {}: {}", user.getId(), e.getMessage());
            throw new ApiException(
                    "Service temporarily unavailable. Please try again later.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    boolean isUnlimited(User user) {
        if (user == null || user.getEmail() == null) {
            return false;
        }
        return unlimitedEmails.contains(user.getEmail().trim().toLowerCase(Locale.ROOT));
    }

    private static Set<String> parseEmails(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
