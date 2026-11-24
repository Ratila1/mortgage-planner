package dev.nosurprise.sms.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO sms.outbox_event (id, event_type, sms_id, status, attempts, next_attempt_at, created_at)
        VALUES (:id, :eventType, :smsId, 'NEW', 0, :now, :now)
        ON CONFLICT (event_type, sms_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventType") String eventType,
                       @Param("smsId") UUID smsId, @Param("now") Instant now);

    /**
     * Inserts SMS_DELIVERED outbox only when a Telegram verification is actually waiting for this SMS.
     * Written in the same transaction as the SMS delivery status transition.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO sms.outbox_event (id, event_type, sms_id, status, attempts, next_attempt_at, created_at)
        SELECT :id, :eventType, :smsId, 'NEW', 0, :now, :now
        WHERE EXISTS (
            SELECT 1 FROM sms.verification_requests v
            WHERE v.sms_id = :smsId AND v.status = 'CREATED'
        )
        ON CONFLICT (event_type, sms_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfPendingVerification(@Param("id") UUID id, @Param("eventType") String eventType,
                                    @Param("smsId") UUID smsId, @Param("now") Instant now);

    /**
     * Reconciliation: creates outbox rows for delivered SMS that still have a pending Telegram
     * verification but no outbox event. Safe to run repeatedly.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO sms.outbox_event (id, event_type, sms_id, status, attempts, next_attempt_at, created_at)
        SELECT gen_random_uuid(), :eventType, v.sms_id, 'NEW', 0, :now, :now
        FROM sms.verification_requests v
        JOIN sms.sms_request s ON s.id = v.sms_id
        WHERE v.status = 'CREATED'
          AND v.sms_id IS NOT NULL
          AND v.expires_at > :now
          AND s.status = 'DELIVERED'
          AND NOT EXISTS (
              SELECT 1 FROM sms.outbox_event o
              WHERE o.event_type = :eventType AND o.sms_id = v.sms_id
          )
        ON CONFLICT (event_type, sms_id) DO NOTHING
        """, nativeQuery = true)
    int reconcileMissingDelivered(@Param("eventType") String eventType, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = """
        WITH claimed AS (
          SELECT id FROM sms.outbox_event
          WHERE status IN ('NEW', 'RETRY')
            AND next_attempt_at <= now()
            AND (locked_until IS NULL OR locked_until < now())
          ORDER BY created_at
          LIMIT :batch
          FOR UPDATE SKIP LOCKED
        )
        UPDATE sms.outbox_event o
        SET locked_until = now() + (CAST(:leaseSeconds AS double precision) * interval '1 second'),
            locked_by = :lockedBy
        FROM claimed
        WHERE o.id = claimed.id
        RETURNING o.id
        """, nativeQuery = true)
    List<UUID> claimBatch(@Param("batch") int batch,
                          @Param("leaseSeconds") double leaseSeconds,
                          @Param("lockedBy") String lockedBy);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE sms.outbox_event
        SET status = 'PROCESSED', processed_at = :now, locked_until = NULL, locked_by = NULL, last_error = NULL
        WHERE id = :id AND status IN ('NEW', 'RETRY')
        """, nativeQuery = true)
    int markProcessed(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE sms.outbox_event
        SET status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'DEAD' ELSE 'RETRY' END,
            attempts = attempts + 1,
            next_attempt_at = CASE WHEN attempts + 1 >= :maxAttempts
                                   THEN next_attempt_at
                                   ELSE now() + (CAST(:backoffSeconds AS double precision) * interval '1 second') END,
            processed_at = CASE WHEN attempts + 1 >= :maxAttempts THEN :now ELSE processed_at END,
            last_error = :error,
            locked_until = NULL,
            locked_by = NULL
        WHERE id = :id AND status IN ('NEW', 'RETRY')
        """, nativeQuery = true)
    int markRetryOrDead(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts,
                        @Param("backoffSeconds") double backoffSeconds,
                        @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE sms.outbox_event
        SET status = 'DEAD', attempts = attempts + 1, processed_at = :now,
            last_error = :error, locked_until = NULL, locked_by = NULL
        WHERE id = :id AND status IN ('NEW', 'RETRY')
        """, nativeQuery = true)
    int markDead(@Param("id") UUID id, @Param("error") String error, @Param("now") Instant now);
}
