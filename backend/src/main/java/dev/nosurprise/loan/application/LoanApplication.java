package dev.nosurprise.loan.application;

import dev.nosurprise.customer.ProfileView;
import dev.nosurprise.loan.calc.LoanQuote;
import dev.nosurprise.loan.application.state.LoanState;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Заявка на микрозайм и сам заём после подписи (MICROLOANS.md §3.8).
 * Публичного setStatus нет: статус меняется только методами approve/reject/cancel/activate/expire/markRepaid,
 * каждый проверяет переход через {@link LoanState#canTransitionTo} и {@link LoanState#validateTransition}.
 * <p>
 * Состояние инкапсулировано в {@link LoanState}; {@link LoanStatus} используется только как идентификатор
 * для JPA, CHECK constraint в БД и OUTBOX-событий.
 */
@Entity
@Table(name = "loan_applications")
public class LoanApplication {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "loan_type_id", nullable = false)
    private UUID loanTypeId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "term_days", nullable = false)
    private int termDays;

    @Embedded
    private LoanTerms terms;

    @Embedded
    private ApplicantSnapshot applicant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanStatus status;

    @Transient
    private LoanState state;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Комментарий админа при одобрении или причина отказа (её видит клиент, §3.3). */
    @Column(name = "decision_comment")
    private String decisionComment;

    @Column(name = "offer_expires_at")
    private Instant offerExpiresAt;

    /** Когда заявка стала конечной: по нему считается срок хранения фото паспорта (§3.11). */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Только для чтения из JPA: пишет её одним условным UPDATE consumer кредитной истории (§4.9).
     * Иначе сохранение заявки с устаревшим значением откатило бы отметку назад.
     */
    @Column(name = "credit_history_synced_event_id", insertable = false, updatable = false)
    private Long creditHistorySyncedEventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected LoanApplication() {
    }

    @PostLoad
    void initState() {
        this.state = LoanState.of(this.status);
    }

    static LoanApplication submit(UUID userId, UUID loanTypeId, LoanQuote quote, ProfileView profile,
                                  UUID kycDocumentId, Instant now) {
        LoanApplication application = new LoanApplication();
        application.id = UUID.randomUUID();
        application.userId = userId;
        application.loanTypeId = loanTypeId;
        application.amount = quote.amount();
        application.termDays = quote.termDays();
        application.terms = LoanTerms.from(quote);
        application.applicant = ApplicantSnapshot.from(profile, kycDocumentId);
        application.status = LoanStatus.SUBMITTED;
        application.state = LoanState.of(LoanStatus.SUBMITTED);
        application.createdAt = now;
        application.updatedAt = now;
        return application;
    }

    public void approve(UUID adminId, String comment, Instant now, Instant offerExpiresAt) {
        changeTo(LoanStatus.APPROVED, adminId, now);
        this.decidedBy = adminId;
        this.decidedAt = now;
        this.decisionComment = blankToNull(comment);
        this.offerExpiresAt = offerExpiresAt;
        this.state.onEnter(this, adminId, now);
    }

    public void reject(UUID adminId, String reason, Instant now) {
        changeTo(LoanStatus.REJECTED, adminId, now);
        this.decidedBy = adminId;
        this.decidedAt = now;
        this.decisionComment = reason.trim();
        this.state.onEnter(this, adminId, now);
    }

    public void cancel(Instant now) {
        changeTo(LoanStatus.CANCELLED, null, now);
        this.state.onEnter(this, null, now);
    }

    public void activate(Instant now) {
        if (isOfferExpired(now)) {
            throw new OfferExpiredException();
        }
        changeTo(LoanStatus.ACTIVE, null, now);
        this.state.onEnter(this, null, now);
    }

    public void expire(Instant now) {
        changeTo(LoanStatus.EXPIRED, null, now);
        this.state.onEnter(this, null, now);
    }

    public void markRepaid(Instant now) {
        changeTo(LoanStatus.REPAID, null, now);
        this.state.onEnter(this, null, now);
    }

    /**
     * Общая смена состояния: проверка допустимости перехода, валидация бизнес-правил,
     * выход из текущего состояния, смена статуса и вход в новое.
     */
    private void changeTo(LoanStatus target, UUID actorId, Instant now) {
        LoanState current = LoanState.of(this.status);
        if (!current.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(this.status, target);
        }
        current.validateTransition(this, target, now);
        current.onExit(this);
        this.status = target;
        this.state = LoanState.of(target);
        this.updatedAt = now;
    }

    public boolean isOfferExpired(Instant now) {
        return offerExpiresAt != null && !now.isBefore(offerExpiresAt);
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getLoanTypeId() {
        return loanTypeId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getTermDays() {
        return termDays;
    }

    public LoanTerms getTerms() {
        return terms;
    }

    public ApplicantSnapshot getApplicant() {
        return applicant;
    }

    public LoanStatus getStatus() {
        return status;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public Instant getOfferExpiresAt() {
        return offerExpiresAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Long getCreditHistorySyncedEventId() {
        return creditHistorySyncedEventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
