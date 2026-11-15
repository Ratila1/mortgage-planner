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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест Redis-адаптера кэша на моках StringRedisTemplate (§6.3):
 * формат ключа calc:{ownerScope}:{paramsHash} — владельцы изолированы, анонимы шарят
 * scope "anon", TTL 1 ч, удаление, отсутствие ключа.
 * Testcontainers сознательно не используются.
 */
@ExtendWith(MockitoExtension.class)
class RedisCalcCacheTest {

    private static final String HASH = "a".repeat(64);
    private static final String CALC_ID = "665f0f0f1e1e1e1e1e1e1e1e";
    private static final String USER_SCOPE = UUID.randomUUID().toString();

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> values;

    @InjectMocks
    RedisCalcCache cache;

    @Test
    @DisplayName("findCalcId читает ключ calc:{ownerScope}:{paramsHash}")
    void findCalcIdReadsPrefixedKey() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("calc:" + USER_SCOPE + ":" + HASH)).thenReturn(CALC_ID);

        assertThat(cache.findCalcId(USER_SCOPE, HASH)).isEqualTo(Optional.of(CALC_ID));
    }

    @Test
    @DisplayName("анонимный расчёт живёт в общем scope 'anon'")
    void anonymousScopeIsShared() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("calc:anon:" + HASH)).thenReturn(CALC_ID);

        assertThat(cache.findCalcId(RedisCalcCache.ANONYMOUS_SCOPE, HASH)).isEqualTo(Optional.of(CALC_ID));
    }

    @Test
    @DisplayName("один и тот же paramsHash у разных владельцев — разные ключи")
    void scopesDoNotCollide() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("calc:" + USER_SCOPE + ":" + HASH)).thenReturn(null);

        assertThat(cache.findCalcId(USER_SCOPE, HASH)).isEmpty();
        verify(values).get("calc:" + USER_SCOPE + ":" + HASH);
        verify(values, never()).get("calc:anon:" + HASH);
    }

    @Test
    @DisplayName("отсутствующий ключ в Redis → Optional.empty")
    void findCalcIdAbsentKey() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("calc:" + USER_SCOPE + ":" + HASH)).thenReturn(null);

        assertThat(cache.findCalcId(USER_SCOPE, HASH)).isEmpty();
    }

    @Test
    @DisplayName("save пишет calcId в ключ calc:{ownerScope}:{paramsHash} с TTL 1 час")
    void saveWritesValueWithOneHourTtl() {
        when(redis.opsForValue()).thenReturn(values);

        cache.save(USER_SCOPE, HASH, CALC_ID);

        verify(values).set("calc:" + USER_SCOPE + ":" + HASH, CALC_ID, Duration.ofHours(1));
    }

    @Test
    @DisplayName("evict удаляет ключ calc:{ownerScope}:{paramsHash}")
    void evictDeletesKey() {
        cache.evict(USER_SCOPE, HASH);

        verify(redis).delete("calc:" + USER_SCOPE + ":" + HASH);
        verifyNoInteractions(values);
    }
}
