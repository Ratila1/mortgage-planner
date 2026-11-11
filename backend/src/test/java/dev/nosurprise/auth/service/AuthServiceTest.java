package dev.nosurprise.auth.service;

import dev.nosurprise.user.Provider;
import dev.nosurprise.user.Role;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Логин/refresh/logout (§7): неверный email и неверный пароль — одна и та же ошибка
 * (существование email не раскрывается), вход до подтверждения почты — 403 EMAIL_NOT_VERIFIED.
 * Проверка пароля — реальный DelegatingPasswordEncoder (bcrypt), как в конфигурации.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String PASSWORD = "Sup3rSecret!";
    private static final String PASSWORD_HASH =
            PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);

    @Mock
    UserService userService;

    @Mock
    TokenService tokenService;

    @Mock
    RefreshTokenService refreshTokenService;

    private final PasswordEncoder passwordEncoder =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final JwtProperties properties =
            new JwtProperties("test-secret-0123456789abcdef0123456789",
                    java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(30));
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

    private AuthService service() {
        return new AuthService(userService, passwordEncoder, tokenService,
                refreshTokenService, properties, clock);
    }

    private UserEntity user(boolean emailVerified) {
        return new UserEntity(USER_ID, "user@example.com", PASSWORD_HASH, emailVerified,
                Provider.LOCAL, null, Instant.EPOCH, Set.of(Role.USER));
    }

    @Test
    @DisplayName("успешный логин: access в теле, refresh выдан и уходит в cookie-значение")
    void loginSuccess() {
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user(true)));
        when(tokenService.generate(USER_ID)).thenReturn("access-jwt");
        when(refreshTokenService.issue(USER_ID, "10.0.0.1"))
                .thenReturn(new IssuedRefreshToken(USER_ID, "opaque",
                        USER_ID + ".opaque", Instant.EPOCH.plusSeconds(100)));

        LoginResult result = service().login("user@example.com", PASSWORD, "10.0.0.1");

        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.expiresIn()).isEqualTo(15 * 60);
        assertThat(result.refreshTokenCookie()).isEqualTo(USER_ID + ".opaque");
    }

    @Test
    @DisplayName("email нормализуется (trim + lowercase) перед поиском")
    void loginNormalizesEmail() {
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user(true)));
        when(tokenService.generate(USER_ID)).thenReturn("access-jwt");
        when(refreshTokenService.issue(any(), any()))
                .thenReturn(new IssuedRefreshToken(USER_ID, "opaque", USER_ID + ".opaque",
                        Instant.EPOCH));

        service().login("  USER@example.com ", PASSWORD, "10.0.0.1");

        verify(userService).findByEmail("user@example.com");
    }

    @Test
    @DisplayName("неизвестный email → InvalidCredentialsException")
    void unknownEmailRejected() {
        when(userService.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().login("ghost@example.com", PASSWORD, "10.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("неверный пароль → InvalidCredentialsException")
    void wrongPasswordRejected() {
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user(true)));

        assertThatThrownBy(() -> service().login("user@example.com", "Wr0ngPass!", "10.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("неверный email и неверный пароль — ОДНА и та же ошибка (email не раскрывается)")
    void sameErrorForUnknownEmailAndWrongPassword() {
        when(userService.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user(true)));

        var unknownEmailError = catchThrowable(
                () -> service().login("ghost@example.com", PASSWORD, "10.0.0.1"));
        var wrongPasswordError = catchThrowable(
                () -> service().login("user@example.com", "Wr0ngPass!", "10.0.0.1"));

        assertThat(unknownEmailError).isExactlyInstanceOf(InvalidCredentialsException.class);
        assertThat(wrongPasswordError).isExactlyInstanceOf(InvalidCredentialsException.class);
        assertThat(unknownEmailError.getMessage())
                .isEqualTo(wrongPasswordError.getMessage());
    }

    @Test
    @DisplayName("email неподтверждён → EmailNotVerifiedException (проверяется после пароля)")
    void unverifiedEmailRejected() {
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> service().login("user@example.com", PASSWORD, "10.0.0.1"))
                .isInstanceOf(EmailNotVerifiedException.class);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("OAuth-пользователь без password_hash → InvalidCredentialsException")
    void oauthUserWithoutPasswordRejected() {
        UserEntity oauthUser = new UserEntity(USER_ID, "oauth@example.com", null, true,
                Provider.GOOGLE, "google-1", Instant.EPOCH, Set.of(Role.USER));
        when(userService.findByEmail("oauth@example.com")).thenReturn(Optional.of(oauthUser));

        assertThatThrownBy(() -> service().login("oauth@example.com", PASSWORD, "10.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("refresh: ротация — новый access, cookie-значение обновлённого refresh")
    void refreshRotatesTokens() {
        when(refreshTokenService.rotate(USER_ID + ".old-token", "10.0.0.1"))
                .thenReturn(new IssuedRefreshToken(USER_ID, "new-token",
                        USER_ID + ".new-token", Instant.EPOCH));
        when(tokenService.generate(USER_ID)).thenReturn("access-jwt");

        LoginResult result = service().refresh(USER_ID + ".old-token", "10.0.0.1");

        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshTokenCookie()).isEqualTo(USER_ID + ".new-token");
    }

    @Test
    @DisplayName("refresh без cookie → InvalidRefreshTokenException")
    void refreshWithoutCookieRejected() {
        assertThatThrownBy(() -> service().refresh(null, "10.0.0.1"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("logout: удаляет текущий refresh-токен; без cookie — идемпотентно (ничего не делает)")
    void logoutRevokesToken() {
        service().logout(USER_ID + ".token-1");
        service().logout(null);

        verify(refreshTokenService, times(1)).revoke(USER_ID + ".token-1");
        verifyNoMoreInteractions(refreshTokenService);
    }
}
