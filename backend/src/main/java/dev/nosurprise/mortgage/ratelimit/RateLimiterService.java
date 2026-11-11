package dev.nosurprise.mortgage.ratelimit;

import reactor.core.publisher.Mono;

public interface RateLimiterService {
    Mono<Boolean> tryAcquire(String key, int limit, long windowSeconds);
    Mono<Long> getRemainingRequests(String key, int limit, long windowSeconds);
    Mono<Long> getRetryAfterSeconds(String key, long windowSeconds);
}







