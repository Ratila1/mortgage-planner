package dev.nosurprise.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceOAuthProvisioningTest {

    @Mock
    UserRepository repository;

    @InjectMocks
    UserService userService;

    @Test
    @DisplayName("новый OAuth-пользователь создаётся с provider, providerId, email_verified")
    void createsNewOAuthUser() {
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity user = userService.provisionOAuthUser("  USER@example.com ", Provider.GOOGLE, "google-1", true);

        assertThat(user.getEmail()).isEqualTo("user@example.com");
        assertThat(user.getProvider()).isEqualTo(Provider.GOOGLE);
        assertThat(user.getProviderId()).isEqualTo("google-1");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getRoles()).containsExactly(Role.USER);
    }

    @Test
    @DisplayName("существующий пользователь связывается, password_hash не перезаписывается")
    void linksExistingUser() {
        UserEntity existing = new UserEntity(UUID.randomUUID(), "user@example.com", "hash", false,
                Provider.LOCAL, null, java.time.Instant.EPOCH, Set.of(Role.USER));
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity user = userService.provisionOAuthUser("user@example.com", Provider.GITHUB, "gh-1", true);

        assertThat(user.getPasswordHash()).isEqualTo("hash");
        assertThat(user.getProvider()).isEqualTo(Provider.GITHUB);
        assertThat(user.getProviderId()).isEqualTo("gh-1");
        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("повторный вход через того же провайдера идемпотентен")
    void idempotentSameProvider() {
        UserEntity existing = new UserEntity(UUID.randomUUID(), "user@example.com", null, true,
                Provider.GOOGLE, "google-1", java.time.Instant.EPOCH, Set.of(Role.USER));
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity user = userService.provisionOAuthUser("user@example.com", Provider.GOOGLE, "google-1", true);

        assertThat(user.getProvider()).isEqualTo(Provider.GOOGLE);
        assertThat(user.getProviderId()).isEqualTo("google-1");
    }

    @Test
    @DisplayName("если пользователь привязан к другому провайдеру, provider не перезаписывается")
    void doesNotOverwriteOtherProvider() {
        UserEntity existing = new UserEntity(UUID.randomUUID(), "user@example.com", null, true,
                Provider.GOOGLE, "google-1", java.time.Instant.EPOCH, Set.of(Role.USER));
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity user = userService.provisionOAuthUser("user@example.com", Provider.GITHUB, "gh-1", true);

        assertThat(user.getProvider()).isEqualTo(Provider.GOOGLE);
        assertThat(user.getProviderId()).isEqualTo("google-1");
    }

    @Test
    @DisplayName("email_verified остаётся true, если пользователь уже был verified")
    void keepsVerified() {
        UserEntity existing = new UserEntity(UUID.randomUUID(), "user@example.com", null, true,
                Provider.LOCAL, null, java.time.Instant.EPOCH, Set.of(Role.USER));
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity user = userService.provisionOAuthUser("user@example.com", Provider.GOOGLE, "g", false);

        assertThat(user.isEmailVerified()).isTrue();
    }
}