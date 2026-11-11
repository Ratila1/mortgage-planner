package dev.nosurprise.offer;

import dev.nosurprise.offer.web.dto.OfferResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfferCacheTest {

    @Test
    @DisplayName("get возвращает empty при отсутствии ключа")
    void emptyWhenMissing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("offers:all")).thenReturn(null);

        OfferCache cache = new OfferCache(redis, new ObjectMapper());
        assertThat(cache.get()).isEmpty();
    }

    @Test
    @DisplayName("put сохраняет JSON, get читает его")
    void roundTrip() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        ObjectMapper mapper = new ObjectMapper();
        OfferCache cache = new OfferCache(redis, mapper);

        OfferResponse offer = new OfferResponse(UUID.randomUUID(), "Bank", "9.50", "10.00", 360, true, Instant.now());
        cache.put(List.of(offer));

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ops).set(org.mockito.ArgumentMatchers.eq("offers:all"), captor.capture(), org.mockito.ArgumentMatchers.any());
        when(ops.get("offers:all")).thenReturn(captor.getValue());

        Optional<List<OfferResponse>> result = cache.get();
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).bankName()).isEqualTo("Bank");
    }

    @Test
    @DisplayName("evict удаляет ключ")
    void evict() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        OfferCache cache = new OfferCache(redis, new ObjectMapper());
        cache.evict();
        verify(redis).delete("offers:all");
    }

    @Test
    @DisplayName("сбой Redis не ломает get")
    void redisFailureIgnored() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RuntimeException("down"));
        OfferCache cache = new OfferCache(redis, new ObjectMapper());
        assertThat(cache.get()).isEmpty();
    }
}