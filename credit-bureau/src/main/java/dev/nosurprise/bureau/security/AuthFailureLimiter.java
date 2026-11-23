package dev.nosurprise.bureau.security;

import dev.nosurprise.bureau.config.BureauProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Защита от перебора ключей (CREDIT_BUREAU.md §8, уровень 2): счётчик неудачных входов по IP в Redis.
 * После N неудач за окно IP получает 429 до конца окна — даже с верным ключом.
 */
@Component
public class AuthFailureLimiter {

    private static final String PREFIX = "bureau:authfail:";

    private final StringRedisTemplate redis;
    private final BureauProperties properties;

    public AuthFailureLimiter(StringRedisTemplate redis, BureauProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Сколько секунд IP ещё заблокирован; 0 — не заблокирован. */
    public long blockedForSeconds(String ip) {
        String value = redis.opsForValue().get(PREFIX + ip);
        if (value == null || Long.parseLong(value) < properties.authFailuresPerWindow()) {
            return 0;
        }
        Long ttl = redis.getExpire(PREFIX + ip, TimeUnit.SECONDS);
        return ttl == null || ttl <= 0 ? properties.authFailureWindow().toSeconds() : ttl;
    }

    public void recordFailure(String ip) {
        String key = PREFIX + ip;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, properties.authFailureWindow());
        }
    }
}
