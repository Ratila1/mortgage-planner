package dev.nosurprise.loan.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import dev.nosurprise.loan.type.LoanType;
import java.util.UUID;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, UUID> {

    Optional<LoanApplication> findByIdAndUserId(UUID id, UUID userId);

    Page<LoanApplication> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<LoanApplication> findByStatus(LoanStatus status, Pageable pageable);

    @Query("select a from LoanApplication a " +
            "where (:status is null or a.status = :status) " +
            "and (:q is null or lower(a.applicant.lastName) like :q " +
            "or lower(a.applicant.firstName) like :q " +
            "or lower(coalesce(a.applicant.middleName, '')) like :q " +
            "or a.applicant.phone like :q " +
            "or exists (select t from LoanType t where t.id = a.loanTypeId and lower(t.name) like :q))")
    Page<LoanApplication> search(@Param("status") LoanStatus status, @Param("q") String q, Pageable pageable);

    boolean existsByUserIdAndStatusIn(UUID userId, List<LoanStatus> statuses);

    /**
     * Истёкшие предложения под блокировкой. SKIP LOCKED: задача на нескольких инстансах
     * не обработает одну заявку дважды (§6).
     */
    @Query(value = "SELECT id FROM loan_applications WHERE status = 'APPROVED' AND offer_expires_at <= :now "
            + "ORDER BY offer_expires_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> lockExpiredOffers(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Фото паспорта, у которых истёк срок хранения (§3.11): заявка отклонена, отменена или истекла
     * раньше границы, и на фото не ссылается ни одна живая или подписанная заявка.
     */
    @Query(value = "SELECT DISTINCT a.kyc_document_id FROM loan_applications a "
            + "WHERE a.status IN ('REJECTED', 'CANCELLED', 'EXPIRED') AND a.closed_at < :before "
            + "AND NOT EXISTS (SELECT 1 FROM loan_applications b WHERE b.kyc_document_id = a.kyc_document_id "
            + "  AND b.status IN ('SUBMITTED', 'APPROVED', 'ACTIVE', 'OVERDUE', 'REPAID'))", nativeQuery = true)
    List<UUID> findKycDocumentsPastRetention(@Param("before") Instant before);

    /**
     * Займы с прошедшей датой возврата под блокировкой (CREDIT_POLICY.md §5.1). SKIP LOCKED: строку, которую
     * сейчас гасит админ, задача пропустит и возьмёт в следующий запуск, если она всё ещё ACTIVE.
     */
    @Query(value = "SELECT id FROM loan_applications WHERE status = 'ACTIVE' AND due_date < :today "
            + "ORDER BY due_date LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> lockOverdue(@Param("today") LocalDate today, @Param("limit") int limit);

    /** Просроченные ровно на :days дней, которым ещё не отправляли напоминание за этот день. */
    @Query(value = "SELECT a.id FROM loan_applications a WHERE a.status = 'OVERDUE' AND a.due_date = :dueDate "
            + "AND NOT EXISTS (SELECT 1 FROM loan_events e WHERE e.application_id = a.id "
            + "  AND e.type = 'OVERDUE_REMINDER_SENT' AND e.details ->> 'day' = CAST(:days AS text)) "
            + "ORDER BY a.id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> lockOverdueWithoutReminder(@Param("dueDate") LocalDate dueDate, @Param("days") int days,
                                          @Param("limit") int limit);

    /**
     * Сводка прошлых заявок клиента для скоринга (CREDIT_POLICY.md §3.3, §8.1). Текущая заявка исключена.
     * Погашенный без даты возврата (подписан до V17) считается погашенным вовремя.
     */
    @Query(value = """
            SELECT count(*) FILTER (WHERE status IN ('ACTIVE', 'OVERDUE', 'REPAID'))                 AS signedLoans,
                   count(*) FILTER (WHERE status = 'REPAID'
                                      AND (due_date IS NULL OR paid_at IS NULL OR paid_at <= due_date)) AS repaidOnTime,
                   count(*) FILTER (WHERE status = 'REPAID' AND paid_at > due_date)                    AS repaidLate,
                   coalesce(max(CASE WHEN status = 'REPAID' AND paid_at > due_date THEN paid_at - due_date
                                     WHEN status = 'OVERDUE' THEN CAST(:today AS date) - due_date
                                     ELSE 0 END), 0)                                                  AS maxOverdueDays,
                   count(*) FILTER (WHERE status = 'OVERDUE') > 0                                     AS overdueNow,
                   count(*) FILTER (WHERE status = 'REJECTED' AND decided_at >= :since)               AS rejectedLast12Months
            FROM loan_applications
            WHERE user_id = :userId AND id <> :excludeId
            """, nativeQuery = true)
    ScoringHistoryRow scoringHistory(@Param("userId") UUID userId, @Param("excludeId") UUID excludeId,
                                     @Param("today") LocalDate today, @Param("since") Instant since);

    /**
     * Заявки, у которых копия в Mongo отстаёт от журнала, и последнее событие старше границы (§4.9, сверка).
     */
    @Query(value = "SELECT a.id FROM loan_applications a "
            + "JOIN LATERAL (SELECT max(e.id) AS last_id, max(e.occurred_at) AS last_at "
            + "  FROM loan_events e WHERE e.application_id = a.id) e ON true "
            + "WHERE e.last_id IS NOT NULL "
            + "AND (a.credit_history_synced_event_id IS NULL OR a.credit_history_synced_event_id < e.last_id) "
            + "AND e.last_at < :before LIMIT :limit", nativeQuery = true)
    List<UUID> findCreditHistoryLagging(@Param("before") Instant before, @Param("limit") int limit);

    @Query("select a.id from LoanApplication a order by a.createdAt")
    List<UUID> findAllIds(Pageable pageable);

    /** Отметка синхронизации только вперёд: поздний consumer со старой версией её не откатит. */
    @Modifying
    @Query(value = "UPDATE loan_applications SET credit_history_synced_event_id = :eventId WHERE id = :id "
            + "AND (credit_history_synced_event_id IS NULL OR credit_history_synced_event_id < :eventId)",
            nativeQuery = true)
    int markCreditHistorySynced(@Param("id") UUID id, @Param("eventId") long eventId);
}
