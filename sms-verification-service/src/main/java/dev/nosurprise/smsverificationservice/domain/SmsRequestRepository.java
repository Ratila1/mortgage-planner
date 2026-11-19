package dev.nosurprise.smsverificationservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmsRequestRepository extends JpaRepository<SmsRequest, UUID> {

    Optional<SmsRequest> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Transactional
    @Query("""
        UPDATE SmsRequest r SET
          r.verifyAttempts = r.verifyAttempts + 1,
          r.updatedAt = CURRENT_TIMESTAMP
        WHERE r.id = :id
          AND r.verifyAttempts < :max
        """)
    int incrementVerifyAttempts(@Param("id") UUID id, @Param("max") int max);

    @Modifying
    @Transactional
    @Query("""
        UPDATE SmsRequest r SET
          r.verifiedAt = CURRENT_TIMESTAMP,
          r.updatedAt = CURRENT_TIMESTAMP
        WHERE r.id = :id
        """)
    void markVerified(@Param("id") UUID id);

    @Query("""
        SELECT r FROM SmsRequest r
        WHERE r.status = 'IN_PROGRESS'
          AND r.attempts < r.maxAttempts
          AND r.nextAttemptAt <= CURRENT_TIMESTAMP
          AND (r.lockedUntil IS NULL OR r.lockedUntil < CURRENT_TIMESTAMP)
        ORDER BY r.nextAttemptAt
        """)
    List<SmsRequest> findPendingForDispatch(int batchSize);

    @Modifying
    @Transactional
    @Query(value = """
        WITH claimed AS (
          SELECT id FROM sms.sms_request
          WHERE status = 'IN_PROGRESS'
            AND attempts < max_attempts
            AND next_attempt_at <= now()
            AND (locked_until IS NULL OR locked_until < now())
          ORDER BY next_attempt_at
          LIMIT :batch
          FOR UPDATE SKIP LOCKED
        )
        UPDATE sms.sms_request r
        SET locked_until = now() + (:leaseMillis * interval '1 millisecond'),
            locked_by = :instance,
            attempts = r.attempts + 1,
            updated_at = now()
        FROM claimed
        WHERE r.id = claimed.id
        RETURNING r.id
        """, nativeQuery = true)
    List<UUID> claimBatch(@Param("instance") String instance, @Param("batch") int batch,
                          @Param("leaseMillis") long leaseMillis);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE sms.sms_request r SET
          status = CASE WHEN :ok THEN 'SUCCESS'
                        WHEN r.attempts >= r.max_attempts THEN 'NOT_SEND'
                        ELSE 'IN_PROGRESS' END,
          sent_at = CASE WHEN :ok THEN now() ELSE r.sent_at END,
          next_attempt_at = CASE WHEN :ok OR r.attempts >= r.max_attempts THEN NULL
                                 ELSE CAST(:nextAttempt AS timestamptz) END,
          last_error = :error,
          provider_message_id = COALESCE(:providerId, r.provider_message_id),
          locked_until = NULL,
          locked_by = NULL,
          updated_at = now()
        WHERE r.id = :id
        """, nativeQuery = true)
    void finishAttempt(
        @Param("id") UUID id,
        @Param("ok") boolean ok,
        @Param("nextAttempt") Instant nextAttempt,
        @Param("error") String error,
        @Param("providerId") String providerId
    );

    @Modifying
    @Transactional
    @Query(value = """
        DELETE FROM sms.sms_request r WHERE r.created_at < :cutoff
    """, nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}