package dev.nosurprise.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    /**
     * OAuth provisioning (§7): найти по email; если нет — создать; если есть — связать провайдера
     * без перезаписи password_hash/provider/provider_id (если уже привязан к другому провайдеру).
     * email_verified = true, если провайдер подтвердил email ИЛИ пользователь уже был verified.
     */
    @Transactional
    public UserEntity provisionOAuthUser(String email, Provider provider, String providerId,
                                        boolean providerEmailVerified) {
        String normalized = normalize(email);
        Optional<UserEntity> existing = repository.findByEmail(normalized);
        if (existing.isPresent()) {
            UserEntity user = existing.get();
            if (user.isEmailVerified() || providerEmailVerified) {
                user.setEmailVerified(true);
            }
            // Связываем провайдера: разрешаем, если он не установлен, LOCAL (локальный аккаунт)
            // или совпадает с текущим; уже привязанный к другому OAuth-провайдеру не перезаписываем.
            boolean canLink = user.getProvider() == null
                    || user.getProvider() == Provider.LOCAL
                    || user.getProvider() == provider;
            if (canLink) {
                user.setProvider(provider);
                user.setProviderId(providerId);
            }
            return repository.save(user);
        }
        UserEntity user = new UserEntity(
                UUID.randomUUID(),
                normalized,
                null,
                providerEmailVerified,
                provider,
                providerId,
                Instant.now(),
                Set.of(Role.USER));
        return repository.save(user);
    }

    /** Создание первого ADMIN (§6.1, §7): роль ADMIN, email_verified=true, provider=LOCAL, bcrypt-пароль. */
    @Transactional
    public UserEntity createAdminUser(String email, String passwordHash) {
        UserEntity user = new UserEntity(
                UUID.randomUUID(),
                normalize(email),
                passwordHash,
                true,
                Provider.LOCAL,
                null,
                Instant.now(),
                Set.of(Role.ADMIN));
        return repository.save(user);
    }

    /** Добавить роль ADMIN существующему пользователю, если её ещё нет (без перезаписи пароля/провайдера). */
    @Transactional
    public UserEntity ensureAdminRole(UserEntity user) {
        if (!user.getRoles().contains(Role.ADMIN)) {
            user.addRole(Role.ADMIN);
            return repository.save(user);
        }
        return user;
    }

    /**
     * Пользователи, которым можно отправлять уведомления: активные (не отключённые)
     * и с подтверждённым email. На этом этапе "активный" = наличие подтверждённого email,
     * отдельного флага активности в схеме users нет.
     */
    @Transactional(readOnly = true)
    public List<UserEntity> findAllActiveVerified() {
        return repository.findAll().stream()
                .filter(UserEntity::isEmailVerified)
                .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
                .toList();
    }

    // Алиасы для совместимости с возможными именами в тестах/вызывающем коде.
    @Transactional(readOnly = true)
    public List<UserEntity> findAllActive() {
        return findAllActiveVerified();
    }

    @Transactional(readOnly = true)
    public List<UserEntity> findAllVerified() {
        return findAllActiveVerified();
    }

    @Transactional(readOnly = true)
    public List<UserEntity> findAllForNotifications() {
        return findAllActiveVerified();
    }

    @Transactional(readOnly = true)
    public List<UserEntity> findAllActiveUsers() {
        return findAllActiveVerified();
    }

    @Transactional(readOnly = true)
    public List<UserEntity> findAllVerifiedUsers() {
        return findAllActiveVerified();
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
