package dev.nosurprise.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fail-fast на секрете (§7): пустой или короткий (&lt; 32 байт = 256 бит для HS256) секрет
 * не должен позволять приложению стартовать.
 */
class JwtPropertiesTest {

    private static final String LONG_SECRET = "0123456789abcdef0123456789abcdef";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"tooshort", "0123456789012345678901234567891"})
    @DisplayName("секрет отсутствует или короче 32 символов → IllegalStateException")
    void weakSecretRejected(String secret) {
        assertThrows(IllegalStateException.class,
                () -> new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(30)));
    }

    @Test
    @DisplayName("валидный секрет и TTL → свойства создаются")
    void validPropertiesAccepted() {
        assertDoesNotThrow(() -> new JwtProperties(
                LONG_SECRET, Duration.ofMinutes(15), Duration.ofDays(30)));
    }

    @Test
    @DisplayName("нулевой или отрицательный TTL → IllegalStateException")
    void invalidTtlRejected() {
        assertThrows(IllegalStateException.class,
                () -> new JwtProperties(LONG_SECRET, Duration.ZERO, Duration.ofDays(30)));
        assertThrows(IllegalStateException.class,
                () -> new JwtProperties(LONG_SECRET, Duration.ofMinutes(15), Duration.ofSeconds(-1)));
    }
}
