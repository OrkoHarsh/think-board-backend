package com.nimbusboard.ai;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private AiQuotaService quotaService;

    @BeforeEach
    void setUp() {
        quotaService = new AiQuotaService(jdbcTemplate, 3, "hrsh0412@gmail.com");
    }

    @Test
    void fourthCall_returns429() {
        User user = user("tester@example.com");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(user.getId()), eq(3)))
                .thenReturn(List.of(1), List.of(2), List.of(3), List.of());

        quotaService.consumeOrThrow(user);
        quotaService.consumeOrThrow(user);
        quotaService.consumeOrThrow(user);

        assertThatThrownBy(() -> quotaService.consumeOrThrow(user))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(api.getMessage()).isEqualTo(AiQuotaService.DAILY_LIMIT_MESSAGE);
                });
    }

    @Test
    void allowlistedEmail_doesNotTouchDatabase() {
        User user = user("hrsh0412@gmail.com");
        for (int i = 0; i < 5; i++) {
            quotaService.consumeOrThrow(user);
        }
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(), any());
    }

    @Test
    void allowlistMatch_isCaseInsensitive() {
        User user = user("Hrsh0412@gmail.com");
        assertThat(quotaService.isUnlimited(user)).isTrue();
        quotaService.consumeOrThrow(user);
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(), any());
    }

    @Test
    void dbFailure_failClosedWith503() {
        User user = user("tester@example.com");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(user.getId()), eq(3)))
                .thenThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> quotaService.consumeOrThrow(user))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static User user(String email) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .password("x")
                .name("Tester")
                .role("USER")
                .build();
    }
}
