package com.nimbusboard.ai;

import com.nimbusboard.auth.models.User;
import com.nimbusboard.auth.models.UserRepository;
import com.nimbusboard.util.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AiQuotaServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("nimbusboard_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.rate-limit.ai-requests-per-day", () -> "3");
        registry.add("app.rate-limit.unlimited-emails", () -> "hrsh0412@gmail.com");
    }

    @Autowired private AiQuotaService aiQuotaService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void fourthCallInADay_returns429() {
        User user = saveUser("quota-cap-" + UUID.randomUUID() + "@example.com");

        aiQuotaService.consumeOrThrow(user);
        aiQuotaService.consumeOrThrow(user);
        aiQuotaService.consumeOrThrow(user);

        assertThatThrownBy(() -> aiQuotaService.consumeOrThrow(user))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(api.getMessage()).isEqualTo(AiQuotaService.DAILY_LIMIT_MESSAGE);
                });

        assertThat(usedCount(user)).isEqualTo(3);
    }

    @Test
    void concurrentConsumes_cannotExceedDailyLimit() throws Exception {
        User user = saveUser("quota-race-" + UUID.randomUUID() + "@example.com");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    aiQuotaService.consumeOrThrow(user);
                    successes.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                        limited.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(successes.get()).isEqualTo(3);
        assertThat(limited.get()).isEqualTo(threads - 3);
        assertThat(usedCount(user)).isEqualTo(3);
    }

    @Test
    void allowlistedEmail_isNeverIncremented() {
        User user = saveUser("hrsh0412@gmail.com");
        for (int i = 0; i < 5; i++) {
            aiQuotaService.consumeOrThrow(user);
        }
        assertThat(usedCount(user)).isZero();
    }

    @Test
    void allowlistMatch_isCaseInsensitive() {
        User user = saveUser("Hrsh0412@gmail.com");
        assertThat(aiQuotaService.isUnlimited(user)).isTrue();
        aiQuotaService.consumeOrThrow(user);
        aiQuotaService.consumeOrThrow(user);
        aiQuotaService.consumeOrThrow(user);
        aiQuotaService.consumeOrThrow(user);
        assertThat(usedCount(user)).isZero();
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .password("hashed")
                .name("Quota Tester")
                .role("USER")
                .build());
    }

    private int usedCount(User user) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(call_count), 0) FROM ai_usage_daily WHERE user_id = ?",
                Integer.class,
                user.getId());
        return count == null ? 0 : count;
    }
}
