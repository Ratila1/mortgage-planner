package dev.nosurprise.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест оконного счётчика на моках StringRedisTemplate (§6.3):
 * ключ ratelimit:calc:{principal}, INCR, EXPIRE 60 с при первом запросе окна,
 * превышение лимита → отказ с Retry-After = остаток TTL.
 * Testcontainers сознательно не используются.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final String USER_ID = "5f0f1e1e-1e1e-1e1e-1e1e-1e1e1e1e1e1e";

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> values;

    @InjectMocks
    RateLimiter limiter;

    private RateLimitProperties properties(int anonymous, int authenticated) {
        RateLimitProperties props = new RateLimitProperties();
        props.setAnonymousPerMinute(anonymous);
        props.setAuthenticatedPerMinute(authenticated);
        return props;
    }

    private void incrementReturns(String key, long count) {
        lenient().when(redis.opsForValue()).thenReturn(values);
        lenient().when(values.increment(key)).thenReturn(count);
    }

    @Test
    @DisplayName("ключ ratelimit:calc:{ip}; ниже лимита → разрешено, EXPIRE ставится при первом INCR")
    void belowLimitAllowsAndSetsWindow() {
        RateLimiter limiter = new RateLimiter(redis, properties(30, 120));
        incrementReturns("ratelimit:calc:" + IP, 1L);

        RateLimitDecision decision = limiter.tryAcquire(IP, 30);

        assertThat(decision.allowed()).isTrue();
        verify(redis).expire("ratelimit:calc:" + IP, 60L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("превышение лимита → отказ, Retry-After = остаток TTL ключа")
    void overLimitLimitedWithRetryAfter() {
        RateLimiter limiter = new RateLimiter(redis, properties(30, 120));
        incrementReturns("ratelimit:calc:" + IP, 31L);
        when(redis.getExpire("ratelimit:calc:" + IP, TimeUnit.SECONDS)).thenReturn(17L);

        RateLimitDecision decision = limiter.tryAcquire(IP, 30);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(17L);
        // окно уже открыто ранее — EXPIRE не обновляется
        verify(redis, never()).expire(anyString(), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("TTL ключа отсутствует/истёк → Retry-After = размер окна (60 с)")
    void missingTtlFallsBackToWindow() {
        RateLimiter limiter = new RateLimiter(redis, properties(30, 120));
        incrementReturns("ratelimit:calc:" + IP, 40L);
        when(redis.getExpire("ratelimit:calc:" + IP, TimeUnit.SECONDS)).thenReturn(-2L);

        RateLimitDecision decision = limiter.tryAcquire(IP, 30);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    @DisplayName("аноним и пользователь имеют разные ключи и лимиты (30 vs 120)")
    void separateKeysAndLimitsForPrincipal() {
        RateLimiter limiter = new RateLimiter(redis, properties(30, 120));
        incrementReturns("ratelimit:calc:" + IP, 5L);
        incrementReturns("ratelimit:calc:" + USER_ID, 100L);

        assertThat(limiter.tryAcquire(IP, 30).allowed()).isTrue();
        assertThat(limiter.tryAcquire(USER_ID, 120).allowed()).isTrue();

        verify(values).increment("ratelimit:calc:" + IP);
        verify(values).increment("ratelimit:calc:" + USER_ID);
    }

    @Test
    @DisplayName("первый запрос пользователя тоже открывает окно EXPIRE 60 с")
    void firstUserRequestOpensWindow() {
        RateLimiter limiter = new RateLimiter(redis, properties(30, 120));
        incrementReturns("ratelimit:calc:" + USER_ID, 1L);

        limiter.tryAcquire(USER_ID, 120);

        verify(redis).expire("ratelimit:calc:" + USER_ID, 60L, TimeUnit.SECONDS);
    }
}
