package dev.nosurprise.common.ratelimit;

/**
 * Результат проверки лимита: разрешено / отказ с Retry-After (секунды до конца окна, §6.3).
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, 0);
    }

    public static RateLimitDecision limited(long retryAfterSeconds) {
        return new RateLimitDecision(false, retryAfterSeconds);
    }
}
