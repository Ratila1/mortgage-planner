package dev.nosurprise.auth.service;

import dev.nosurprise.auth.event.VerificationEmailRequested;
import dev.nosurprise.auth.web.dto.RegisterRequest;
import dev.nosurprise.user.Provider;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест регистрации: bcrypt-хэш, email_verified=false (создаёт UserService),
 * verification-токен на нового пользователя, событие письма; существующий email
 * не создаёт ничего и не публикует события (существование email не раскрывается).
 */
@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {

    private static final String EMAIL = "New.User@Example.COM";
    private static final String PASSWORD = "Sup3rSecret!";
    private static final String HASH = "{bcrypt}$2a$10$hash";

    @Mock
    UserService userService;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    VerificationTokenStore tokenStore;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    RegisterService service;

    private UserEntity user(String email) {
        return new UserEntity(java.util.UUID.randomUUID(), email, HASH, false,
                Provider.LOCAL, null, java.time.Instant.now(), Set.of(dev.nosurprise.user.Role.USER));
    }

    @Test
    @DisplayName("новый email: пароль кодируется bcrypt, создаётся пользователь, токен и событие письма")
    void registerNewEmail() {
        when(userService.findByEmail("new.user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
        UserEntity created = user("new.user@example.com");
        when(userService.createLocalUser("new.user@example.com", HASH)).thenReturn(created);
        when(tokenStore.create(created.getId())).thenReturn("token-1");

        service.register(new RegisterRequest(EMAIL, PASSWORD));

        verify(tokenStore).create(created.getId());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(
                new VerificationEmailRequested(created.getId(), "new.user@example.com", "token-1"));
    }

    @Test
    @DisplayName("email нормализуется: trim + lowercase при поиске и создании")
    void registerNormalizesEmail() {
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn(HASH);
        when(userService.createLocalUser(eq("user@example.com"), anyString()))
                .thenReturn(user("user@example.com"));
        when(tokenStore.create(any())).thenReturn("t");

        service.register(new RegisterRequest("  User@Example.COM  ", PASSWORD));

        verify(userService).findByEmail("user@example.com");
        verify(userService).createLocalUser(eq("user@example.com"), anyString());
    }

    @Test
    @DisplayName("существующий email: ничего не создаётся, токен и событие не публикуются")
    void registerExistingEmailIsSilent() {
        when(userService.findByEmail("taken@example.com")).thenReturn(Optional.of(user("taken@example.com")));

        service.register(new RegisterRequest("taken@example.com", PASSWORD));

        verify(passwordEncoder, never()).encode(anyString());
        verify(userService, never()).createLocalUser(anyString(), anyString());
        verify(tokenStore, never()).create(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
