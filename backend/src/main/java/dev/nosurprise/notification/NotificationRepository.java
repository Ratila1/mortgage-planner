package dev.nosurprise.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByUserIdAndType(UUID userId, String type);

    List<Notification> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    java.util.Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Удаляет PDF_READY-уведомления пользователя по конкретному сценарию
     * (scenarioId лежит в jsonb payload, поэтому нужен native-запрос).
     */
    @Modifying
    @Query(value = "DELETE FROM notifications "
            + "WHERE user_id = :userId AND type = 'PDF_READY' "
            + "AND payload->>'scenarioId' = :scenarioId", nativeQuery = true)
    int deletePdfReadyForScenario(@Param("userId") UUID userId, @Param("scenarioId") String scenarioId);
}