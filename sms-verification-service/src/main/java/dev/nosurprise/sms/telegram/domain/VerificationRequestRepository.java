package dev.nosurprise.sms.telegram.domain;

import dev.nosurprise.sms.domain.SmsStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, UUID> {

    Optional<VerificationRequest> findByIdAndUserId(UUID id, UUID userId);

    Optional<VerificationRequest> findBySmsIdAndStatus(UUID smsId, SmsStatus status);

    /**
     * Верификации, которые ждут доставки в Telegram, хотя SMS уже доставлена. Нужны для catch-up:
     * dispatch мог успешно отправить SMS, но упасть на внешнем вызове в Telegram.
     */
    @Query(value = """
        SELECT v.sms_id
        FROM sms.verification_requests v
        JOIN sms.sms_request s ON s.id = v.sms_id
        WHERE v.status = 'CREATED'
          AND v.sms_id IS NOT NULL
          AND v.expires_at > now()
          AND s.status = 'DELIVERED'
        ORDER BY v.created_at
        LIMIT :limit
        """, nativeQuery = true)
    List<UUID> findTelegramCatchupSmsIds(@Param("limit") int limit);

    /** Retention: раньше таблица не чистилась вообще и росла бесконечно. */
    @Modifying
    @Transactional
    @Query("delete from VerificationRequest v where v.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
