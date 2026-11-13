package dev.nosurprise.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест публичного сервиса модуля user: создание LOCAL-пользователя
 * (email_verified=false, роль USER, provider LOCAL) и поиск по email.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository repository;

    @InjectMocks
    UserService service;

    @Test
    @DisplayName("createLocalUser: сохраняет пользователя с email_verified=false, ролью USER, provider LOCAL")
    void createLocalUserDefaults() {
        service.createLocalUser("user@example.com", "bcrypt-hash");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(repository).save(captor.capture());
        UserEntity saved = captor.getValue();

        // id не задаётся сервисом: UUID генерирует Hibernate при persist
        // (иначе Spring Data вызвал бы merge и StaleObjectStateException).
        assertThat(saved.getId()).isNull();
        assertThat(saved.getEmail()).isEqualTo("user@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(saved.isEmailVerified()).isFalse();
        assertThat(saved.getProvider()).isEqualTo(Provider.LOCAL);
        assertThat(saved.getProviderId()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getRoles()).containsExactly(Role.USER);
    }

    @Test
    @DisplayName("createLocalUser: сущность создаётся без id, чтобы Hibernate сделал persist, а не merge")
    void createLocalUserLeavesIdToHibernate() {
        service.createLocalUser("a@example.com", "h1");
        service.createLocalUser("b@example.com", "h2");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(user -> assertThat(user.getId()).isNull());
    }

    @Test
    @DisplayName("findByEmail делегирует в репозиторий")
    void findByEmailDelegates() {
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        assertThat(service.findByEmail("user@example.com")).isEmpty();
        verify(repository).findByEmail("user@example.com");
    }
}
