package dev.nosurprise.calc.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест Redis-адаптера кэша на моках StringRedisTemplate (§6.3):
 * формат ключа calc:{paramsHash}, TTL 1 ч, удаление, отсутствие ключа.
 * Testcontainers сознательно не используются.
 */
@ExtendWith(MockitoExtension.class)
class RedisCalcCacheTest {

    private static final String HASH = "a".repeat(64);
    private static final String CALC_ID = "665f0f0f1e1e1e1e1e1e1e1e";

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> values;

    @InjectMocks
    RedisCalcCache cache;

    @Test
    @DisplayName("findCalcId читает ключ calc:{paramsHash}")
    void findCalcIdReadsPrefixedKey() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("calc:" + HASH)).thenReturn(CALC_ID);

        assertThat(cache.findCalcId(HASH)).isEqualTo(Optional.of(CALC_ID));
    }

    @Test
    @DisplayName("отсутствующий ключ в Redis → Optional.empty")
    void findCalcIdAbsentKey() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("calc:" + HASH)).thenReturn(null);

        assertThat(cache.findCalcId(HASH)).isEmpty();
    }

    @Test
    @DisplayName("save пишет calcId в ключ calc:{paramsHash} с TTL 1 час")
    void saveWritesValueWithOneHourTtl() {
        when(redis.opsForValue()).thenReturn(values);

        cache.save(HASH, CALC_ID);

        verify(values).set("calc:" + HASH, CALC_ID, Duration.ofHours(1));
    }

    @Test
    @DisplayName("evict удаляет ключ calc:{paramsHash}")
    void evictDeletesKey() {
        cache.evict(HASH);

        verify(redis).delete("calc:" + HASH);
        verifyNoInteractions(values);
    }
}
