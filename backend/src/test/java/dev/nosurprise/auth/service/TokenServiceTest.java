package dev.nosurprise.auth.service;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Access JWT (§7): HS256, subject = userId, TTL 15 мин из свойств; неверная подпись,
 * мусор и истёкший токен — InvalidAccessTokenException. Секрет — только через свойства.
 */
class TokenServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtProperties properties =
            new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(30));
    private final TokenService tokenService = new TokenService(properties, clock);

    @Test
    @DisplayName("generate → validate: round-trip, subject = userId")
    void generateAndValidateRoundTrip() {
        UUID userId = UUID.randomUUID();

        String token = tokenService.generate(userId);

        assertEquals(userId, assertDoesNotThrow(() -> tokenService.validate(token)));
    }

    @Test
    @DisplayName("JWT подписан HS256, exp = now + 15 минут")
    void tokenIsHs256WithAccessTtl() throws Exception {
        String token = tokenService.generate(UUID.randomUUID());

        SignedJWT jwt = SignedJWT.parse(token);
        assertEquals("HS256", jwt.getHeader().getAlgorithm().getName());
        assertEquals(java.util.Date.from(NOW.plus(Duration.ofMinutes(15))),
                jwt.getJWTClaimsSet().getExpirationTime());
    }

    @Test
    @DisplayName("неверная подпись (другой секрет) → InvalidAccessTokenException")
    void wrongSecretIsRejected() {
        TokenService otherKey = new TokenService(
                new JwtProperties("another-secret-0123456789abcdef0123456789",
                        Duration.ofMinutes(15), Duration.ofDays(30)),
                clock);
        String token = tokenService.generate(UUID.randomUUID());

        assertThrows(InvalidAccessTokenException.class, () -> otherKey.validate(token));
    }

    @Test
    @DisplayName("мусор вместо токена → InvalidAccessTokenException")
    void garbageIsRejected() {
        assertThrows(InvalidAccessTokenException.class, () -> tokenService.validate("not-a-jwt"));
    }

    @Test
    @DisplayName("истёкший токен → InvalidAccessTokenException")
    void expiredTokenIsRejected() {
        Clock later = Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        TokenService validatorAtLaterTime = new TokenService(properties, later);
        String token = tokenService.generate(UUID.randomUUID());

        assertThrows(InvalidAccessTokenException.class, () -> validatorAtLaterTime.validate(token));
    }

    @Test
    @DisplayName("sha256Hex — стабильный hex длиной 64")
    void sha256HexStable() {
        assertEquals(64, TokenService.sha256Hex("opaque-token").length());
        assertEquals(TokenService.sha256Hex("opaque-token"), TokenService.sha256Hex("opaque-token"));
    }
}
