package dev.nosurprise.sms.domain;

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

    @Query("select r.status from SmsRequest r where r.id = :id")
    Optional<SmsStatus> findStatusById(@Param("id") UUID id);

    /** Смена статуса с аудитом: updated_at всегда, verified_at — только при переходе в VERIFIED. */
    default int markStatus(UUID id, SmsStatus status, Instant now) {
        return markStatusRaw(id, status.name(), now);
    }

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE sms.sms_request
        SET status = :status,
            updated_at = :now,
            verified_at = CASE WHEN :status = 'VERIFIED' THEN :now ELSE verified_at END
        WHERE id = :id
        """, nativeQuery = true)
    int markStatusRaw(@Param("id") UUID id, @Param("status") String status, @Param("now") Instant now);

    /**
     * Гасит коды с истёкшим TTL: в Redis они уже сгорели, отправлять их и принимать по ним verify нельзя.
     * Строки с живой lease не трогаем — такая отправка ещё в полёте, и её исход решает {@code finishAttempt}.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE sms.sms_request
        SET status = 'EXPIRED', updated_at = :now
        WHERE status IN ('CREATED', 'DELIVERED')
          AND expires_at IS NOT NULL
          AND expires_at < :now
          AND (locked_until IS NULL OR locked_until < :now)
        """, nativeQuery = true)
    int expireStale(@Param("now") Instant now);

    /**
     * Закрывает «зомби»-строки: попытки уже исчерпаны (например, инстанс упал между claim и
     * {@code finishAttempt}), а статус остался CREATED. {@code claimBatch} такие строки не берёт,
     * поэтому без этого шага они висели бы вечно и не попадали ни в NOT_DELIVERED, ни в отчёты.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE sms.sms_request
        SET status = 'NOT_DELIVERED', updated_at = now()
        WHERE status = 'CREATED'
          AND attempts >= :max
          AND (locked_until IS NULL OR locked_until < now())
        """, nativeQuery = true)
    int closeExhausted(@Param("max") int max);

    /**
     * Захват пачки под отправку. Lease ({@code locked_until}) не даёт второму инстансу взять строку,
     * которая уже в полёте, а backoff считается от {@code last_attempt_at}, а не от {@code created_at}.
     * Коды с истёкшим {@code expires_at} не захватываются: отправлять их уже бессмысленно.
     */
    @Modifying
    @Transactional
    @Query(value = """
        WITH claimed AS (
          SELECT id FROM sms.sms_request
          WHERE status = 'CREATED'
            AND attempts < :max
            AND (expires_at IS NULL OR expires_at > now())
            AND (locked_until IS NULL OR locked_until < now())
            AND (last_attempt_at IS NULL
                 OR last_attempt_at
                    + (CAST(CASE WHEN attempts <= 1 THEN :firstBackoffSeconds
                                 ELSE :secondBackoffSeconds END AS double precision)
                       * interval '1 second') <= now())
          ORDER BY created_at
          LIMIT :batch
          FOR UPDATE SKIP LOCKED
        )
        UPDATE sms.sms_request r
        SET attempts = r.attempts + 1,
            last_attempt_at = now(),
            locked_until = now() + (CAST(:leaseSeconds AS double precision) * interval '1 second'),
            locked_by = :lockedBy,
            updated_at = now()
        FROM claimed
        WHERE r.id = claimed.id
        RETURNING r.id
        """, nativeQuery = true)
    List<UUID> claimBatch(@Param("max") int max, @Param("batch") int batch,
                          @Param("firstBackoffSeconds") double firstBackoffSeconds,
                          @Param("secondBackoffSeconds") double secondBackoffSeconds,
                          @Param("leaseSeconds") double leaseSeconds,
                          @Param("lockedBy") String lockedBy);

    /**
     * Продлевает lease непосредственно перед отправкой строки. Пачка захватывается под один общий lease,
     * а обрабатывается последовательно: без продления последние строки пачки «просрочились» бы, пока
     * инстанс ещё отправляет первые. Возвращает 0, если строка уже не наша (другой инстанс) или закрыта.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE sms.sms_request
        SET locked_until = now() + (CAST(:leaseSeconds AS double precision) * interval '1 second')
        WHERE id = :id
          AND status = 'CREATED'
          AND locked_by = :lockedBy
        """, nativeQuery = true)
    int renewLease(@Param("id") UUID id, @Param("lockedBy") String lockedBy,
                   @Param("leaseSeconds") double leaseSeconds);

    /**
     * Фиксирует исход попытки. Статус двигается только из CREATED: погашенная (EXPIRED/VERIFIED)
     * строка не должна вернуться в очередь с уже мёртвым кодом.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE sms.sms_request r
        SET status = CASE WHEN :ok THEN 'DELIVERED'
                         WHEN r.attempts >= :max THEN 'NOT_DELIVERED'
                         ELSE 'CREATED' END,
            sent_at = CASE WHEN :ok THEN now() ELSE r.sent_at END,
            locked_until = NULL,
            locked_by = NULL,
            updated_at = now()
        WHERE r.id = :id
          AND r.status = 'CREATED'
        """, nativeQuery = true)
    void finishAttempt(
        @Param("id") UUID id,
        @Param("ok") boolean ok,
        @Param("max") int max
    );

    @Modifying
    @Transactional
    @Query(value = """
        DELETE FROM sms.sms_request r
        WHERE r.created_at < :cutoff
    """, nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
