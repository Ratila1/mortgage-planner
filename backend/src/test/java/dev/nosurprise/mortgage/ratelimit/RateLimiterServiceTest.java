package dev.nosurprise.mortgage.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimiterServiceTest {

    private RateLimiterService rateLimiterService;
    private ReactiveRedisTemplate<String, String> redisTemplate;
    private ReactiveZSetOperations<String, String> zSetOps;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveRedisTemplate.class);
        zSetOps = mock(ReactiveZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        rateLimiterService = new RateLimiterServiceImpl(redisTemplate);
    }

    @Nested
    @DisplayName("Rate limiting (К12)")
    class RateLimitTests {

        @Test
        @DisplayName("tryAcquire_withinLimit_returnsTrue")
        void tryAcquire_withinLimit_returnsTrue() {
            // given
            String key = "ip:192.168.1.1";
            int limit = 10;
            long windowSeconds = 60;
            long now = System.currentTimeMillis() / 1000;

            when(zSetOps.removeRangeByScore(anyString(), any())).thenReturn(Mono.just(0L));
            when(zSetOps.size(anyString())).thenReturn(Mono.just(5L)); // 5 requests already
            when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

            // when
            Mono<Boolean> result = rateLimiterService.tryAcquire(key, limit, windowSeconds);

            // then
            StepVerifier.create(result)
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("tryAcquire_exceedsLimit_returnsFalse")
        void tryAcquire_exceedsLimit_returnsFalse() {
            // given
            String key = "ip:192.168.1.1";
            int limit = 10;
            long windowSeconds = 60;

            when(zSetOps.removeRangeByScore(anyString(), any())).thenReturn(Mono.just(0L));
            when(zSetOps.size(anyString())).thenReturn(Mono.just(10L)); // already at limit

            // when
            Mono<Boolean> result = rateLimiterService.tryAcquire(key, limit, windowSeconds);

            // then
            StepVerifier.create(result)
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("tryAcquire_multipleKeys_independentLimits")
        void tryAcquire_multipleKeys_independentLimits() {
            // given
            int limit = 10;
            long windowSeconds = 60;

            when(zSetOps.removeRangeByScore(anyString(), any())).thenReturn(Mono.just(0L));
            when(zSetOps.size(anyString())).thenReturn(Mono.just(5L));
            when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

            // when
            Mono<Boolean> result1 = rateLimiterService.tryAcquire("ip:192.168.1.1", limit, windowSeconds);
            Mono<Boolean> result2 = rateLimiterService.tryAcquire("ip:192.168.1.2", limit, windowSeconds);

            // then
            StepVerifier.create(result1.zipWith(result2))
                    .assertNext(tuple -> {
                        assertThat(tuple.getT1()).isTrue();
                        assertThat(tuple.getT2()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("getRemainingRequests_returnsCorrectCount")
        void getRemainingRequests_returnsCorrectCount() {
            // given
            String key = "ip:192.168.1.1";
            int limit = 10;
            long windowSeconds = 60;

            when(zSetOps.removeRangeByScore(anyString(), any())).thenReturn(Mono.just(0L));
            when(zSetOps.size(anyString())).thenReturn(Mono.just(3L));

            // when
            Mono<Long> result = rateLimiterService.getRemainingRequests(key, limit, windowSeconds);

            // then
            StepVerifier.create(result)
                    .expectNext(7L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("getRemainingRequests_atLimit_returnsZero")
        void getRemainingRequests_atLimit_returnsZero() {
            // given
            String key = "ip:192.168.1.1";
            int limit = 10;
            long windowSeconds = 60;

            when(zSetOps.removeRangeByScore(anyString(), any())).thenReturn(Mono.just(0L));
            when(zSetOps.size(anyString())).thenReturn(Mono.just(10L));

            // when
            Mono<Long> result = rateLimiterService.getRemainingRequests(key, limit, windowSeconds);

            // then
            StepVerifier.create(result)
                    .expectNext(0L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("getRemainingRequests_overLimit_returnsZero")
        void getRemainingRequests_overLimit_returnsZero() {
            // given
            String key = "ip:192.168.1.1";
            int limit = 10;
            long windowSeconds = 60;

            when(zSetOps.removeRangeByScore(anyString(), any())).thenReturn(Mono.just(0L));
            when(zSetOps.size(anyString())).thenReturn(Mono.just(15L));

            // when
            Mono<Long> result = rateLimiterService.getRemainingRequests(key, limit, windowSeconds);

            // then
            StepVerifier.create(result)
                    .expectNext(0L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("getRetryAfterSeconds_withOldestRequest_returnsCorrectSeconds")
        void getRetryAfterSeconds_withOldestRequest_returnsCorrectSeconds() {
            // given
            String key = "ip:192.168.1.1";
            long windowSeconds = 60;
            long now = System.currentTimeMillis() / 1000;
            long oldestTimestamp = now - 30; // 30 seconds ago

            when(zSetOps.range(anyString(), any())).thenReturn(Flux.just(oldestTimestamp + ":" + UUID.randomUUID()));

            // when
            Mono<Long> result = rateLimiterService.getRetryAfterSeconds(key, windowSeconds);

            // then
            StepVerifier.create(result)
                    .expectNext(30L) // 60 - 30 = 30 seconds until window expires
                    .verifyComplete();
        }

        @Test
        @DisplayName("getRetryAfterSeconds_emptySet_returnsZero")
        void getRetryAfterSeconds_emptySet_returnsZero() {
            // given
            String key = "ip:192.168.1.1";
            long windowSeconds = 60;

            when(zSetOps.range(anyString(), any())).thenReturn(Flux.empty());

            // when
            Mono<Long> result = rateLimiterService.getRetryAfterSeconds(key, windowSeconds);

            // then
            StepVerifier.create(result)
                    .expectNext(0L)
                    .verifyComplete();
        }
    }
}







