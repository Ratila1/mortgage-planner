package dev.nosurprise.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceEligibleUsersTest {

    @Mock
    UserRepository repository;

    @InjectMocks
    UserService userService;

    @Test
    @DisplayName("возвращает только пользователей с подтверждённым email и непустым email")
    void filtersEligibleUsers() {
        UserEntity verified = new UserEntity(UUID.randomUUID(), "verified@example.com", "hash", true,
                Provider.LOCAL, null, java.time.Instant.EPOCH, java.util.Set.of(Role.USER));
        UserEntity unverified = new UserEntity(UUID.randomUUID(), "unverified@example.com", "hash", false,
                Provider.LOCAL, null, java.time.Instant.EPOCH, java.util.Set.of(Role.USER));
        UserEntity noEmail = new UserEntity(UUID.randomUUID(), "   ", "hash", true,
                Provider.LOCAL, null, java.time.Instant.EPOCH, java.util.Set.of(Role.USER));

        when(repository.findAll()).thenReturn(List.of(verified, unverified, noEmail));

        assertThat(userService.findAllActiveVerified()).containsExactly(verified);
    }
}