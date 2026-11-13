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

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Подтверждение почты: валидный токен → email_verified=true и токен удалён;
 * отсутствующий/истёкший токен или неизвестный пользователь → VerifyTokenInvalidException;
 * повторное подтверждение (email уже verified) идемпотентно.
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    private static final String TOKEN = "token-abc";

    @Mock
    VerificationTokenStore tokenStore;

    @Mock
    UserService userService;

    @InjectMocks
    VerificationService service;

    private UserEntity user(boolean verified) {
        return new UserEntity(UUID.randomUUID(), "user@example.com", "h", verified,
                Provider.LOCAL, null, Instant.now(), Set.of(Role.USER));
    }

    @Test
    @DisplayName("валидный токен: пользователь подтверждается, токен удаляется")
    void validTokenConfirmsUser() {
        UUID userId = UUID.randomUUID();
        when(tokenStore.read(TOKEN)).thenReturn(Optional.of(userId));
        UserEntity unverified = user(false);
        when(userService.findById(userId)).thenReturn(Optional.of(unverified));

        service.verify(TOKEN);

        verify(userService).markEmailVerified(unverified);
        verify(tokenStore).delete(TOKEN);
    }

    @Test
    @DisplayName("отсутствующий/истёкший токен → VerifyTokenInvalidException, пользователь не ищется")
    void unknownToken() {
        when(tokenStore.read(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(TOKEN)).isInstanceOf(VerifyTokenInvalidException.class);

        verify(userService, never()).findById(any());
        verify(tokenStore, never()).delete(TOKEN);
    }

    @Test
    @DisplayName("токен указывает на несуществующего пользователя → VerifyTokenInvalidException")
    void tokenPointsToMissingUser() {
        when(tokenStore.read(TOKEN)).thenReturn(Optional.of(UUID.randomUUID()));
        when(userService.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(TOKEN)).isInstanceOf(VerifyTokenInvalidException.class);

        verify(tokenStore, never()).delete(TOKEN);
    }

    @Test
    @DisplayName("повторное подтверждение: email_verified уже true → идемпотентно, токен удалён")
    void repeatedConfirmationIsIdempotent() {
        UUID userId = UUID.randomUUID();
        when(tokenStore.read(TOKEN)).thenReturn(Optional.of(userId));
        UserEntity alreadyVerified = user(true);
        when(userService.findById(userId)).thenReturn(Optional.of(alreadyVerified));

        service.verify(TOKEN);

        verify(userService, never()).markEmailVerified(alreadyVerified);
        verify(tokenStore).delete(TOKEN);
    }

    @Test
    @DisplayName("null-токен тоже даёт понятную ошибку VERIFY_TOKEN_INVALID")
    void nullToken() {
        when(tokenStore.read(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(null)).isInstanceOf(VerifyTokenInvalidException.class);
    }
}
