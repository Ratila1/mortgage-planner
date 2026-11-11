package dev.nosurprise.mortgage.ratelimit;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;

@Service
public class RateLimiterServiceImpl implements RateLimiterService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public RateLimiterServiceImpl(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, int limit, long windowSeconds) {
        String redisKey = "ratelimit:" + key;
        long now = System.currentTimeMillis() / 1000;
        double windowStart = (double) (now - windowSeconds);

        // Use Redis sorted set for sliding window rate limiting
        return redisTemplate.opsForZSet()
                .removeRangeByScore(redisKey, Range.closed(0.0, windowStart))
                .then(redisTemplate.opsForZSet().size(redisKey))
                .flatMap(currentCount -> {
                    if (currentCount >= limit) {
                        return Mono.just(false);
                    }
                    String member = now + ":" + java.util.UUID.randomUUID();
                    return redisTemplate.opsForZSet()
                            .add(redisKey, member, (double) now)
                            .flatMap(added -> redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds + 1)))
                            .thenReturn(true);
                });
    }

    @Override
    public Mono<Long> getRemainingRequests(String key, int limit, long windowSeconds) {
        String redisKey = "ratelimit:" + key;
        long now = System.currentTimeMillis() / 1000;
        double windowStart = (double) (now - windowSeconds);

        return redisTemplate.opsForZSet()
                .removeRangeByScore(redisKey, Range.closed(0.0, windowStart))
                .then(redisTemplate.opsForZSet().size(redisKey))
                .map(currentCount -> Math.max(0, limit - currentCount));
    }

    @Override
    public Mono<Long> getRetryAfterSeconds(String key, long windowSeconds) {
        String redisKey = "ratelimit:" + key;
        long now = System.currentTimeMillis() / 1000;

        return redisTemplate.opsForZSet()
                .range(redisKey, Range.closed(0L, 0L))
                .next()
                .map(oldest -> {
                    if (oldest == null) return 0L;
                    String[] parts = oldest.split(":");
                    long oldestTimestamp = Long.parseLong(parts[0]);
                    return Math.max(1, oldestTimestamp + windowSeconds - now);
                })
                .defaultIfEmpty(0L);
    }
}







