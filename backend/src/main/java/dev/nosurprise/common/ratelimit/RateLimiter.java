package dev.nosurprise.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Окно-счётчик в Redis (§6.3): ключ {@code ratelimit:calc:{ip или userId}},
 * INCR + EXPIRE 60 с при первом запросе окна. Превышение лимита → отказ с
 * Retry-After = остаток TTL ключа.
 * Redis недоступен → RuntimeException (решение о пропуске запроса принимает фильтр).
 */
@Component
public class RateLimiter {

    static final String KEY_PREFIX = "ratelimit:calc:";

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public RateLimitDecision tryAcquire(String principal, int limitPerMinute) {
        String key = KEY_PREFIX + principal;
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            throw new IllegalStateException("redis INCR returned null for " + key);
        }
        if (count == 1L) {
            redis.expire(key, properties.getWindow().toSeconds(), TimeUnit.SECONDS);
        }
        if (count <= limitPerMinute) {
            return RateLimitDecision.allow();
        }
        return RateLimitDecision.limited(retryAfterSeconds(key));
    }

    private long retryAfterSeconds(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            return properties.getWindow().toSeconds();
        }
        return ttl;
    }
}
