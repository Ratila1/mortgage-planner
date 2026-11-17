package dev.nosurprise.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к users. Модули не ходят в чужие репозитории — наружу только UserService (§4).
 */
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    @org.springframework.data.jpa.repository.Query("select u.blocked from UserEntity u where u.id = :id")
    Optional<Boolean> findBlockedById(@org.springframework.data.repository.query.Param("id") UUID id);
}
