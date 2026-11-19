package dev.nosurprise.common.otp;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Пауза между SMS с кодом (MICROLOANS.md §3.10, §4.6): не чаще одного сообщения в 60 секунд.
 * Ключ в Redis с TTL 60 с; второй запрос внутри окна → {@link CodeCooldownException} → 429 RATE_LIMITED.
 * Если отправка не удалась, ключ снимается {@link #release(String)}: провайдер сообщение не принял,
 * и пользователь не должен ждать минуту.
 */
@Component
public class OtpCooldown {

    static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    public OtpCooldown(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Атомарный SETNX с TTL: два параллельных запроса не пройдут оба. */
    public void tryAcquire(String key) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", WINDOW);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new CodeCooldownException();
        }
    }

    public void release(String key) {
        redis.delete(key);
    }
}
