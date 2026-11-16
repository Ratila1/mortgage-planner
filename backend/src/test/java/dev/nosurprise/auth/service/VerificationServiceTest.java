package dev.nosurprise.auth.service;

import dev.nosurprise.user.Provider;
import dev.nosurprise.user.Role;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Подтверждение почты + мгновенный вход: валидный токен → email_verified=true,
 * токен удалён (одноразовость), сессия выдана; отсутствующий/истёкший токен или
 * неизвестный пользователь → VerifyTokenInvalidException; повторное использование
 * токена (после удаления) даёт ту же ошибку — сессию по старой ссылке не получить.
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    private static final String TOKEN = "token-abc";

    @Mock
    VerificationTokenStore tokenStore;

    @Mock
    UserService userService;

    @Mock
    TokenService tokenService;

    @Mock
    RefreshTokenService refreshTokenService;

    @InjectMocks
    VerificationService service;

    /** Сущность с тем же userId, что лежит в токене, — иначе стабы не совпадут. */
    private UserEntity user(UUID userId, boolean verified) {
        return new UserEntity(userId, "user@example.com", "h", verified,
                Provider.LOCAL, null, Instant.now(), Set.of(Role.USER));
    }

    private JwtProperties jwtProperties() {
        return new JwtProperties("test-secret-0123456789abcdef0123456789",
                Duration.ofMinutes(15), Duration.ofDays(30));
    }

    private VerificationService serviceWithSessionDeps() {
        return new VerificationService(tokenStore, userService,
                tokenService, refreshTokenService, jwtProperties());
    }

    @Test
    @DisplayName("валидный токен: пользователь подтверждается, токен удаляется, сессия выдана")
    void validTokenConfirmsUserAndLogsIn() {
        UUID userId = UUID.randomUUID();
        when(tokenStore.read(TOKEN)).thenReturn(Optional.of(userId));
        UserEntity unverified = user(userId, false);
        when(userService.findById(userId)).thenReturn(Optional.of(unverified));
        when(tokenService.generate(userId, Set.of(Role.USER))).thenReturn("access-token");
        when(refreshTokenService.issue(userId, "127.0.0.1"))
                .thenReturn(new IssuedRefreshToken(userId, "opaque", userId + ".opaque", Instant.EPOCH));

        LoginResult result = serviceWithSessionDeps().consume(TOKEN, "127.0.0.1");

        verify(userService).markEmailVerified(unverified);
        verify(tokenStore).delete(TOKEN);
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshTokenCookie()).isEqualTo(userId + ".opaque");
    }

    @Test
    @DisplayName("отсутствующий/истёкший токен → VerifyTokenInvalidException, пользователь не ищется")
    void unknownToken() {
        when(tokenStore.read(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consume(TOKEN, "127.0.0.1"))
                .isInstanceOf(VerifyTokenInvalidException.class);

        verify(userService, never()).findById(any());
        verify(tokenStore, never()).delete(TOKEN);
    }

    @Test
    @DisplayName("токен указывает на несуществующего пользователя → VerifyTokenInvalidException")
    void tokenPointsToMissingUser() {
        when(tokenStore.read(TOKEN)).thenReturn(Optional.of(UUID.randomUUID()));
        when(userService.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consume(TOKEN, "127.0.0.1"))
                .isInstanceOf(VerifyTokenInvalidException.class);

        verify(tokenStore, never()).delete(TOKEN);
    }

    @Test
    @DisplayName("email_verified уже true → отметка не повторяется, сессия всё равно выдаётся")
    void alreadyVerifiedStillLogsIn() {
        UUID userId = UUID.randomUUID();
        when(tokenStore.read(TOKEN)).thenReturn(Optional.of(userId));
        UserEntity alreadyVerified = user(userId, true);
        when(userService.findById(userId)).thenReturn(Optional.of(alreadyVerified));
        when(tokenService.generate(userId, Set.of(Role.USER))).thenReturn("access-token");
        when(refreshTokenService.issue(userId, "127.0.0.1"))
                .thenReturn(new IssuedRefreshToken(userId, "opaque", userId + ".opaque", Instant.EPOCH));

        LoginResult result = serviceWithSessionDeps().consume(TOKEN, "127.0.0.1");

        verify(userService, never()).markEmailVerified(alreadyVerified);
        verify(tokenStore).delete(TOKEN);
        assertThat(result.accessToken()).isEqualTo("access-token");
    }

    @Test
    @DisplayName("null-токен тоже даёт понятную ошибку VERIFY_TOKEN_INVALID")
    void nullToken() {
        when(tokenStore.read(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consume(null, "127.0.0.1"))
                .isInstanceOf(VerifyTokenInvalidException.class);
    }
}
