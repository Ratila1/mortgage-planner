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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест Redis-хранилища verification-токенов (§6.3): ключ verify:{token} → userId,
 * TTL 24 ч; read по отсутствующему ключу → empty; delete удаляет ключ.
 */
@ExtendWith(MockitoExtension.class)
class VerificationTokenStoreTest {

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> values;

    @InjectMocks
    VerificationTokenStore store;

    @Test
    @DisplayName("create пишет verify:{token} → userId с TTL 24 ч и возвращает токен")
    void createStoresKeyWithTtl() {
        when(redis.opsForValue()).thenReturn(values);

        String token = store.create(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));

        assertThat(token).hasSize(43).doesNotContain("=", "+", "/");
        verify(values).set("verify:" + token, "550e8400-e29b-41d4-a716-446655440000",
                Duration.ofHours(24));
    }

    @Test
    @DisplayName("read возвращает userId из ключа verify:{token}")
    void readReturnsUserId() {
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("verify:token-1")).thenReturn(userId.toString());

        assertThat(store.read("token-1")).isEqualTo(Optional.of(userId));
    }

    @Test
    @DisplayName("read по отсутствующему ключу → Optional.empty")
    void readAbsent() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("verify:token-1")).thenReturn(null);

        assertThat(store.read("token-1")).isEmpty();
    }

    @Test
    @DisplayName("delete удаляет ключ verify:{token}")
    void deleteRemovesKey() {
        store.delete("token-1");

        verify(redis).delete("verify:token-1");
    }
}
