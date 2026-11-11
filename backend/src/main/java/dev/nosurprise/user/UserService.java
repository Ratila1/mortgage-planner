package dev.nosurprise.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Публичный сервис модуля user (§4): модули общаются через него, не через чужие репозитории.
 * Создание LOCAL-пользователя: email_verified = false до подтверждения почты, роль USER.
 */
@Service
public class UserService {

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserEntity createLocalUser(String email, String passwordHash) {
        UserEntity user = new UserEntity(
                UUID.randomUUID(),
                email,
                passwordHash,
                false,
                Provider.LOCAL,
                null,
                Instant.now(),
                Set.of(Role.USER));
        return repository.save(user);
    }

    @Transactional(readOnly = true)
    public Optional<UserEntity> findByEmail(String email) {
        return repository.findByEmail(email);
    }

    @Transactional(readOnly = true)
    public Optional<UserEntity> findById(UUID id) {
        return repository.findById(id);
    }

    /** Подтверждение почты идемпотентно: повторная установка email_verified безопасна. */
    @Transactional
    public void markEmailVerified(UserEntity user) {
        user.setEmailVerified(true);
        repository.save(user);
    }
}
