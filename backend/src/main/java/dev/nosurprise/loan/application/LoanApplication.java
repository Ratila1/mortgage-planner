package dev.nosurprise.loan.application;

import dev.nosurprise.customer.ProfileView;
import dev.nosurprise.loan.calc.LoanQuote;
import dev.nosurprise.loan.application.state.LoanState;
import dev.nosurprise.loan.application.state.LoanStateMetrics;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Заявка на микрозайм и сам заём после подписи (MICROLOANS.md §3.8).
 * Публичного setStatus нет: статус меняется только методами approve/reject/cancel/activate/expire/markOverdue/
 * markRepaid, каждый проверяет переход через {@link LoanState#canTransitionTo} и {@link LoanState#validateTransition}.
 * <p>
 * Состояние инкапсулировано в {@link LoanState}; {@link LoanStatus} используется только как идентификатор
 * для JPA, CHECK constraint в БД и OUTBOX-событий.
 * Итог скоринга (CREDIT_POLICY.md §6) хранится здесь же: без него админ не одобрит заявку.
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

    /** Подпись дана при подаче: телефон был подтверждён SMS до создания заявки (V20). */
    @Column(name = "signed_at_submit", nullable = false)
    private boolean signedAtSubmit;

    /** Когда заявка стала конечной: по нему считается срок хранения фото паспорта (§3.11). */
    @Column(name = "closed_at")
    private Instant closedAt;

    /** Дата возврата: дата подписи (пояс бизнеса) + срок. После неё ACTIVE становится OVERDUE. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Фактическая дата оплаты, её указывает админ при погашении (CREDIT_POLICY.md §5.1). */
    @Column(name = "paid_at")
    private LocalDate paidAt;

    @Column(name = "scoring_request_id")
    private UUID scoringRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scoring_status")
    private ScoringStatus scoringStatus;

    @Column(name = "scoring_requested_at")
    private Instant scoringRequestedAt;

    @Column(name = "scoring_completed_at")
    private Instant scoringCompletedAt;

    @Column(precision = 5, scale = 4)
    private BigDecimal score;

    @Column(name = "score_points")
    private Integer scorePoints;

    @Column(name = "rating_class")
    private String ratingClass;

    @Column(precision = 7, scale = 6)
    private BigDecimal pd;

    @Enumerated(EnumType.STRING)
    @Column(name = "scoring_decision")
    private ScoringDecision scoringDecision;

    @Column(name = "rules_version")
    private Integer rulesVersion;

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
                                  UUID kycDocumentId, boolean signedAtSubmit, Instant now) {
        LoanApplication application = new LoanApplication();
        application.id = UUID.randomUUID();
        application.userId = userId;
        application.loanTypeId = loanTypeId;
        application.amount = quote.amount();
        application.termDays = quote.termDays();
        application.terms = LoanTerms.from(quote);
        application.applicant = ApplicantSnapshot.from(profile, kycDocumentId);
        application.signedAtSubmit = signedAtSubmit;
        application.status = LoanStatus.SUBMITTED;
        application.state = LoanState.of(LoanStatus.SUBMITTED);
        application.createdAt = now;
        application.updatedAt = now;
        application.state.onEnter(application, userId, now);
        LoanStateMetrics.recordTransition(null, LoanStatus.SUBMITTED, application, userId, now);
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

    /** Подпись договора: дата возврата = дата подписи в поясе бизнеса + срок (CREDIT_POLICY.md §5.1). */
    public void activate(Instant now, ZoneId zone) {
        if (isOfferExpired(now)) {
            throw new OfferExpiredException();
        }
        changeTo(LoanStatus.ACTIVE, null, now);
        this.dueDate = LocalDate.ofInstant(now, zone).plusDays(termDays);
        this.state.onEnter(this, null, now);
    }

    public void activate(Instant now) {
        activate(now, java.time.ZoneId.of("Europe/Minsk"));
    }

    /** Дата возврата прошла без погашения. Переводит только ночная задача. */
    public void markOverdue(Instant now) {
        changeTo(LoanStatus.OVERDUE, null, now);
        this.state.onEnter(this, null, now);
    }

    public void expire(Instant now) {
        changeTo(LoanStatus.EXPIRED, null, now);
        this.state.onEnter(this, null, now);
    }

    /**
     * Погашение из ACTIVE или OVERDUE. paidAt — фактическая дата оплаты: заём, отмеченный из OVERDUE,
     * но оплаченный до срока, записывается как погашенный вовремя (CREDIT_POLICY.md §5.1).
     */
    public void markRepaid(Instant now, LocalDate paidAt, LocalDate today) {
        if (paidAt.isAfter(today)) {
            throw new InvalidPaidDateException("paidAt must not be in the future");
        }
        // Запас в день: дата подачи — в UTC, а дата оплаты — в поясе бизнеса.
        if (paidAt.isBefore(LocalDate.ofInstant(createdAt, ZoneOffset.UTC).minusDays(1))) {
            throw new InvalidPaidDateException("paidAt must not be earlier than the application date");
        }
        changeTo(LoanStatus.REPAID, null, now);
        this.paidAt = paidAt;
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
        LoanStateMetrics.recordTransition(this.status, target, this, actorId, now);
        current.onExit(this);
        this.status = target;
        this.state = LoanState.of(target);
        this.updatedAt = now;
    }

    /**
     * Дни просрочки: для погашенного — по фактической дате оплаты, для OVERDUE — на сегодня.
     * null — заём не выдан или подписан до появления даты возврата.
     */
    public Integer overdueDays(LocalDate today) {
        if (dueDate == null) {
            return null;
        }
        LocalDate end = status == LoanStatus.REPAID ? paidAt : status == LoanStatus.OVERDUE ? today : null;
        if (end == null) {
            return status == LoanStatus.ACTIVE ? 0 : null;
        }
        return (int) Math.max(0, ChronoUnit.DAYS.between(dueDate, end));
    }

    /** Новый запрос скоринга: старый результат сбрасывается, ответ с прежним requestId будет проигнорирован. */
    public void requestScoring(UUID requestId, Instant now) {
        this.scoringRequestId = requestId;
        this.scoringStatus = ScoringStatus.PENDING;
        this.scoringRequestedAt = now;
        this.scoringCompletedAt = null;
        this.score = null;
        this.scorePoints = null;
        this.ratingClass = null;
        this.pd = null;
        this.scoringDecision = null;
        this.rulesVersion = null;
    }

    /**
     * Записывает результат скоринга. false — ответ устарел (был повторный запрос) или уже записан:
     * повторная доставка ничего не меняет.
     */
    public boolean applyScoring(UUID requestId, ScoringStatus status, BigDecimal score, Integer points,
                                String ratingClass, BigDecimal pd, ScoringDecision decision, int rulesVersion,
                                Instant now) {
        if (!requestId.equals(scoringRequestId) || scoringStatus != ScoringStatus.PENDING) {
            return false;
        }
        if (status == ScoringStatus.PENDING) {
            throw new IllegalArgumentException("scoring result must be COMPLETED or NOT_CALCULATED");
        }
        this.scoringStatus = status;
        this.score = score;
        this.scorePoints = points;
        this.ratingClass = ratingClass;
        this.pd = pd;
        this.scoringDecision = decision;
        this.rulesVersion = rulesVersion;
        this.scoringCompletedAt = now;
        this.updatedAt = now;
        return true;
    }

    /**
     * Админ может одобрить только посчитанную заявку (CREDIT_POLICY.md §6.3). NOT_CALCULATED — сервис посчитал
     * и явно сказал «ручное рассмотрение»; PENDING и старые заявки без скоринга — нельзя.
     */
    public boolean isScoredForDecision() {
        return scoringStatus == ScoringStatus.COMPLETED || scoringStatus == ScoringStatus.NOT_CALCULATED;
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

    public boolean isSignedAtSubmit() {
        return signedAtSubmit;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getPaidAt() {
        return paidAt;
    }

    public UUID getScoringRequestId() {
        return scoringRequestId;
    }

    public ScoringStatus getScoringStatus() {
        return scoringStatus;
    }

    public Instant getScoringRequestedAt() {
        return scoringRequestedAt;
    }

    public Instant getScoringCompletedAt() {
        return scoringCompletedAt;
    }

    public BigDecimal getScore() {
        return score;
    }

    public Integer getScorePoints() {
        return scorePoints;
    }

    public String getRatingClass() {
        return ratingClass;
    }

    public BigDecimal getPd() {
        return pd;
    }

    public ScoringDecision getScoringDecision() {
        return scoringDecision;
    }

    public Integer getRulesVersion() {
        return rulesVersion;
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
