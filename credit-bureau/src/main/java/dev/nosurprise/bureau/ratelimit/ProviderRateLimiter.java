package dev.nosurprise.bureau.ratelimit;

import dev.nosurprise.bureau.common.ApiException;
import dev.nosurprise.bureau.common.ErrorCode;
import dev.nosurprise.bureau.security.ProviderPrincipal;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Лимит запросов провайдера (CREDIT_BUREAU.md §8, уровень 3): фиксированные окна «минута» и «сутки» (UTC)
 * в Redis. Оба счётчика увеличиваются одним Lua-скриптом — атомарно, без гонок между инстансами бюро.
 * Превышение любого окна → 429 RATE_LIMITED с Retry-After до конца окна.
 */
@Component
public class ProviderRateLimiter {

    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local minute = redis.call('INCR', KEYS[1])
            if minute == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local day = redis.call('INCR', KEYS[2])
            if day == 1 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end
            return {minute, day}
            """, List.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public ProviderRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Засчитывает запрос; превышен лимит — исключение. Возвращает остаток в минутном окне (для заголовков). */
    public Quota consume(ProviderPrincipal provider) {
        Instant now = clock.instant();
        long epochSecond = now.getEpochSecond();
        long minute = epochSecond / 60;
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        long secondsToNextMinute = 60 - epochSecond % 60;
        long secondsToNextDay = day.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC) - epochSecond;

        String base = "bureau:rl:" + provider.providerId();
        List<?> counters = redis.execute(SCRIPT, List.of(base + ":m:" + minute, base + ":d:" + day),
                String.valueOf(secondsToNextMinute + 1), String.valueOf(secondsToNextDay + 1));
        long minuteCount = ((Number) counters.get(0)).longValue();
        long dayCount = ((Number) counters.get(1)).longValue();

        if (dayCount > provider.requestsPerDay()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                    "daily request limit exceeded", Math.max(1, secondsToNextDay));
        }
        if (minuteCount > provider.requestsPerMinute()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                    "per-minute request limit exceeded", Math.max(1, secondsToNextMinute));
        }
        return new Quota(provider.requestsPerMinute(), Math.max(0, provider.requestsPerMinute() - minuteCount));
    }

    public record Quota(int limit, long remaining) {
    }
}
