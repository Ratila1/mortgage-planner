package dev.nosurprise.offer;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import dev.nosurprise.offer.web.dto.OfferResponse;
import java.util.List;
import java.util.Optional;

/**
 * Redis-кэш offers:all (§6.3): список активных предложений, TTL 5 минут.
 * Сбой Redis не ломает GET /api/offers или admin-операции.
 */
@Component
public class OfferCache {

    static final String KEY = "offers:all";
    static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public OfferCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Optional<List<OfferResponse>> get() {
        try {
            String json = redis.opsForValue().get(KEY);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, new TypeReference<>() {
            }));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public void put(List<OfferResponse> offers) {
        try {
            redis.opsForValue().set(KEY, objectMapper.writeValueAsString(offers), TTL);
        } catch (RuntimeException ex) {
            // ignore: кэш best-effort
        }
    }

    public void evict() {
        try {
            redis.delete(KEY);
        } catch (RuntimeException ex) {
            // ignore
        }
    }
}