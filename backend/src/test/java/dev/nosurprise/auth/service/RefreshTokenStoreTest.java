package dev.nosurprise.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест Redis-хранилища refresh-токенов (§6.3): ключ refresh:{userId}:{sha256(token)} →
 * метаданные, TTL 30 дн; read отсутствующего/повреждённого ключа → empty; delete удаляет ключ.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> values;

    @InjectMocks
    RefreshTokenStore store;

    @Test
    @DisplayName("create пишет refresh:{userId}:{sha256(token)} → метаданные с TTL 30 дн")
    void createStoresKeyWithTtl() {
        when(redis.opsForValue()).thenReturn(values);
        RefreshTokenMetadata metadata = new RefreshTokenMetadata(Instant.EPOCH, "127.0.0.1");

        store.create(USER_ID, "opaque-token", metadata);

        String expectedKey = RefreshTokenStore.KEY_PREFIX + USER_ID + ":"
                + TokenService.sha256Hex("opaque-token");
        verify(values).set(eq(expectedKey), eq(Instant.EPOCH + "|127.0.0.1"),
                eq(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("в значении нет самого opaque-токена — только SHA-256 в имени ключа")
    void rawTokenNeverStored() {
        when(redis.opsForValue()).thenReturn(values);

        store.create(USER_ID, "opaque-token", new RefreshTokenMetadata(Instant.EPOCH, null));

        verify(values).set(org.mockito.ArgumentMatchers.contains(
                        TokenService.sha256Hex("opaque-token")),
                org.mockito.ArgumentMatchers.argThat(
                        value -> !((String) value).contains("opaque-token")),
                eq(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("read разбирает метаданные из значения createdAt|issuedIp")
    void readReturnsMetadata() {
        when(redis.opsForValue()).thenReturn(values);
        String key = RefreshTokenStore.KEY_PREFIX + USER_ID + ":"
                + TokenService.sha256Hex("opaque-token");
        when(values.get(key)).thenReturn("2030-01-01T00:00:00Z|127.0.0.1");

        var metadata = store.read(USER_ID, "opaque-token");

        assertThat(metadata).contains(new RefreshTokenMetadata(
                Instant.parse("2030-01-01T00:00:00Z"), "127.0.0.1"));
    }

    @Test
    @DisplayName("read по отсутствующему ключу → Optional.empty")
    void readAbsent() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

        assertThat(store.read(USER_ID, "unknown")).isEmpty();
    }

    @Test
    @DisplayName("read повреждённого значения → Optional.empty (трактуем как отсутствие токена)")
    void readCorruptedValue() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(org.mockito.ArgumentMatchers.anyString())).thenReturn("garbage");

        assertThat(store.read(USER_ID, "corrupted")).isEmpty();
    }

    @Test
    @DisplayName("delete удаляет ключ refresh:{userId}:{sha256(token)}")
    void deleteRemovesKey() {
        store.delete(USER_ID, "opaque-token");

        verify(redis).delete(RefreshTokenStore.KEY_PREFIX + USER_ID + ":"
                + TokenService.sha256Hex("opaque-token"));
    }
}
