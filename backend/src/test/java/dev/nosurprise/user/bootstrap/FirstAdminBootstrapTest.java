package dev.nosurprise.user.bootstrap;

import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirstAdminBootstrapTest {

    @Test
    @DisplayName("пустые env — безопасный пропуск")
    void blankEnvSkips() {
        UserService userService = mock(UserService.class);
        FirstAdminBootstrap bootstrap = new FirstAdminBootstrap(userService, mock(org.springframework.security.crypto.password.PasswordEncoder.class));
        ReflectionTestUtils.setField(bootstrap, "adminEmail", "");
        ReflectionTestUtils.setField(bootstrap, "adminPassword", "secret");

        bootstrap.run(null);

        verify(userService, never()).findByEmail(any());
    }

    @Test
    @DisplayName("создаёт ADMIN, если пользователя нет")
    void createsAdmin() {
        UserService userService = mock(UserService.class);
        org.springframework.security.crypto.password.PasswordEncoder encoder =
                mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        FirstAdminBootstrap bootstrap = new FirstAdminBootstrap(userService, encoder);
        ReflectionTestUtils.setField(bootstrap, "adminEmail", "Admin@Example.com");
        ReflectionTestUtils.setField(bootstrap, "adminPassword", "secret");
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(encoder.encode("secret")).thenReturn("hash");

        bootstrap.run(null);

        verify(userService).createAdminUser("admin@example.com", "hash");
    }

    @Test
    @DisplayName("существующий пользователь получает роль ADMIN без перезаписи")
    void ensuresAdminRoleForExisting() {
        UserService userService = mock(UserService.class);
        FirstAdminBootstrap bootstrap = new FirstAdminBootstrap(userService,
                mock(org.springframework.security.crypto.password.PasswordEncoder.class));
        ReflectionTestUtils.setField(bootstrap, "adminEmail", "admin@example.com");
        ReflectionTestUtils.setField(bootstrap, "adminPassword", "secret");
        UserEntity existing = new UserEntity(UUID.randomUUID(), "admin@example.com", "hash", false,
                dev.nosurprise.user.Provider.LOCAL, null, java.time.Instant.EPOCH,
                java.util.Set.of(dev.nosurprise.user.Role.USER));
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(existing));

        bootstrap.run(null);

        verify(userService).ensureAdminRole(existing);
    }
}