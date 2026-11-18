package dev.nosurprise.loan.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
            + "  AND b.status IN ('SUBMITTED', 'APPROVED', 'ACTIVE', 'REPAID'))", nativeQuery = true)
    List<UUID> findKycDocumentsPastRetention(@Param("before") Instant before);

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
