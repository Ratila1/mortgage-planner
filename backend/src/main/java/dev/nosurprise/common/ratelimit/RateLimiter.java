package dev.nosurprise.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Окно-счётчик в Redis (§6.3): ключ {@code ratelimit:{scope}:{ip или userId}},
 * INCR + EXPIRE 60 с при первом запросе окна. Превышение лимита → отказ с
 * Retry-After = остаток TTL ключа.
 * Redis недоступен → RuntimeException (решение о пропуске запроса принимает фильтр).
 * <p>
 * Scope — часть ключа: /api/calc, login и otp/verify не должны делить один счётчик,
 * иначе 30 расчётов за минуту выжигают лимит логина (и наоборот) — взаимный DoS.
 * INCR и EXPIRE атомарны (Lua): при падении процесса между ними ключ остался бы
 * без TTL и принципал получил бы перманентный 429 до ручной чистки Redis.
 */
@Component
public class RateLimiter {

    private static final RedisScript<Long> INCR_WINDOW = RedisScript.of(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """,
            Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Скоупы ключей — должны совпадать с константами в RateLimitFilter. */
    public static final String SCOPE_CALC = "calc";
    public static final String SCOPE_AUTH_LOGIN = "auth-login";
    public static final String SCOPE_AUTH_OTP_VERIFY = "auth-otp-verify";
    public static final String SCOPE_AUTH_REGISTER = "auth-register";
    public static final String SCOPE_AUTH_OTP_REQUEST = "auth-otp-request";
    public static final String SCOPE_AUTH_REFRESH = "auth-refresh";
    public static final String SCOPE_AUTH_LOGOUT = "auth-logout";

    public RateLimitDecision tryAcquire(String scope, String principal, int limitPerMinute) {
        String key = "ratelimit:" + scope + ":" + principal;
        Long count = redis.execute(INCR_WINDOW, List.of(key),
                Long.toString(properties.getWindow().toSeconds()));
        if (count == null) {
            throw new IllegalStateException("redis INCR returned null for " + key);
        }
        if (count <= limitPerMinute) {
            return RateLimitDecision.allow();
        }
        return RateLimitDecision.limited(retryAfterSeconds(key));
    }

    private long retryAfterSeconds(String key) {
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            return properties.getWindow().toSeconds();
        }
        return ttl;
    }
}
