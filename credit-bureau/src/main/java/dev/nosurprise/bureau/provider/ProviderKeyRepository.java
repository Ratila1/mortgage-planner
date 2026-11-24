package dev.nosurprise.bureau.provider;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderKeyRepository extends JpaRepository<ProviderKey, UUID> {

    Optional<ProviderKey> findByPrefix(String prefix);

    List<ProviderKey> findByProviderIdOrderByCreatedAtAsc(UUID providerId);

    Optional<ProviderKey> findByIdAndProviderId(UUID id, UUID providerId);

    /** Отдельный UPDATE, а не сохранение сущности: отметка использования не конфликтует с отзывом ключа. */
    @Modifying
    @Query("update ProviderKey k set k.lastUsedAt = :at where k.id = :id")
    int touch(@Param("id") UUID id, @Param("at") Instant at);
}
