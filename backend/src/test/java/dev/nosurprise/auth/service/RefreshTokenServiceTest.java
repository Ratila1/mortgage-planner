package dev.nosurprise.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refresh-токены (§7): opaque (не JWT), cookie-значение {userId}.{token}, ротация —
 * старый токен удаляется и становится невалидным, logout отзывает токен идемпотентно.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Mock
    RefreshTokenStore store;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtProperties properties =
            new JwtProperties("test-secret-0123456789abcdef0123456789",
                    Duration.ofMinutes(15), Duration.ofDays(30));

    private RefreshTokenService service() {
        return new RefreshTokenService(store, properties, clock);
    }

    @Test
    @DisplayName("issue: opaque-токен (не JWT), cookie {userId}.{token}, метаданные в Redis, TTL 30 дн")
    void issueStoresOpaqueToken() {
        var issued = service().issue(USER_ID, "10.0.0.1");

        assertThat(issued.token()).hasSize(43);
        assertThat(issued.token()).doesNotContain("."); // не JWT: нет сегментов через точки
        assertThat(issued.cookieValue()).isEqualTo(USER_ID + "." + issued.token());
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        verify(store).create(eq(USER_ID), eq(issued.token()),
                eq(new RefreshTokenMetadata(NOW, "10.0.0.1")));
    }

    @Test
    @DisplayName("rotate: валидный токен → старый удалён, новый выдан тому же userId")
    void rotateIssuesNewTokenAndDeletesOld() {
        String oldCookie = RefreshTokenService.cookieValue(USER_ID, "old-token");
        when(store.read(USER_ID, "old-token")).thenReturn(Optional.of(
                new RefreshTokenMetadata(NOW.minus(Duration.ofDays(1)), "10.0.0.1")));

        var rotated = service().rotate(oldCookie, "10.0.0.2");

        assertThat(rotated.userId()).isEqualTo(USER_ID);
        assertThat(rotated.token()).isNotEqualTo("old-token");
        verify(store).delete(USER_ID, "old-token");
        verify(store).create(eq(USER_ID), eq(rotated.token()), any(RefreshTokenMetadata.class));
    }

    @Test
    @DisplayName("rotate: после ротации старый токен невалиден (ключ удалён из Redis)")
    void oldTokenInvalidAfterRotation() {
        when(store.read(USER_ID, "old-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().rotate(
                RefreshTokenService.cookieValue(USER_ID, "old-token"), "10.0.0.2"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(store, never()).create(any(), any(), any(RefreshTokenMetadata.class));
    }

    @Test
    @DisplayName("rotate: мусор в cookie → InvalidRefreshTokenException")
    void rotateRejectsGarbageCookie() {
        assertThatThrownBy(() -> service().rotate("not-a-cookie", "10.0.0.2"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service().rotate(null, "10.0.0.2"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("revoke (logout): удаляет ключ текущего токена")
    void revokeDeletesToken() {
        service().revoke(RefreshTokenService.cookieValue(USER_ID, "token-1"));

        verify(store).delete(USER_ID, "token-1");
    }
}
