package dev.nosurprise.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест оконного счётчика на моках StringRedisTemplate (§6.3):
 * ключ ratelimit:{scope}:{principal}, атомарный INCR+EXPIRE через Lua-скрипт,
 * превышение лимита → отказ с Retry-After = остаток TTL.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final String USER_ID = "5f0f1e1e-1e1e-1e1e-1e1e-1e1e1e1e1e1e";

    @Mock
    StringRedisTemplate redis;

    private RateLimitProperties properties(int anonymous, int authenticated) {
        RateLimitProperties props = new RateLimitProperties();
        props.setAnonymousPerMinute(anonymous);
        props.setAuthenticatedPerMinute(authenticated);
        return props;
    }

    @Test
    @DisplayName("ключ ratelimit:calc:{ip}; ниже лимита → разрешено, окно 60 с передаётся в Lua-скрипт")
    void belowLimitAllowsAndPassesWindow() {
        RateLimiter limiter = new RateLimiter(redis, properties(30, 120));
        when(redis.execute(any(RedisScript.class), eq(List.of("ratelimit:calc:" + IP)), eq("60"))).thenReturn(1L);

        RateLimitDecision decision = limiter.tryAcquire(RateLimiter.SCOPE_CALC, IP, 30);

        assertThat(decision.allowed()).isTrue();
        verify(redis).execute(any(RedisScript.class), eq(List.of("ratelimit:calc:" + IP)), eq("60"));
    }

    @Test
    @DisplayName("превышение лимита → отказ, Retry-After = остаток TTL ключа")
    void overLimitLimitedWithRetryAfter() {
        RateLimiter limiter = new RateLimiter(redis, properties(30, 120));
        when(redis.execute(any(RedisScript.class), eq(List.of("ratelimit:calc:" + IP)), eq("60"))).thenReturn(31L);
        when(redis.getExpire("ratelimit:calc:" + IP, TimeUnit.SECONDS)).thenReturn(17L);

        RateLimitDecision decision = limiter.tryAcquire(RateLimiter.SCOPE_CALC, IP, 30);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(17L);
    }

    @Test
    @DisplayName("TTL ключа отсутствует/истёк → Retry-After = размер окна (60 с)")
    void missingTtlFallsBackToWindow() {
        RateLimiter limiter = new RateLimiter(redis, properties(30, 120));
        when(redis.execute(any(RedisScript.class), eq(List.of("ratelimit:calc:" + IP)), eq("60"))).thenReturn(40L);
        when(redis.getExpire("ratelimit:calc:" + IP, TimeUnit.SECONDS)).thenReturn(-2L);

        RateLimitDecision decision = limiter.tryAcquire(RateLimiter.SCOPE_CALC, IP, 30);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    @DisplayName("аноним и пользователь имеют разные ключи и лимиты (30 vs 120)")
    void separateKeysAndLimitsForPrincipal() {
        RateLimiter limiter = new RateLimiter(redis, properties(30, 120));
        when(redis.execute(any(RedisScript.class), eq(List.of("ratelimit:calc:" + IP)), eq("60"))).thenReturn(5L);
        when(redis.execute(any(RedisScript.class), eq(List.of("ratelimit:calc:" + USER_ID)), eq("60"))).thenReturn(100L);

        assertThat(limiter.tryAcquire(RateLimiter.SCOPE_CALC, IP, 30).allowed()).isTrue();
        assertThat(limiter.tryAcquire(RateLimiter.SCOPE_CALC, USER_ID, 120).allowed()).isTrue();

        verify(redis).execute(any(RedisScript.class), eq(List.of("ratelimit:calc:" + IP)), eq("60"));
        verify(redis).execute(any(RedisScript.class), eq(List.of("ratelimit:calc:" + USER_ID)), eq("60"));
    }
}
