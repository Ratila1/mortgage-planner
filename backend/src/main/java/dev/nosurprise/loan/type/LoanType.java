package dev.nosurprise.loan.type;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Тип микрозайма — продукт под цель (MICROLOANS.md §3.1). Не удаляется, а выключается:
 * на него ссылаются старые заявки. Изменения типа не влияют на поданные заявки — у них снимок условий.
 */
@Entity
@Table(name = "loan_types")
public class LoanType {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "min_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal maxAmount;

    @Column(name = "min_term_days", nullable = false)
    private int minTermDays;

    @Column(name = "max_term_days", nullable = false)
    private int maxTermDays;

    @Column(name = "daily_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal dailyRate;

    /** Фиксированная надбавка типа в BYN, прибавляется к сумме к возврату (§3.1). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal modifier;

    @Column(nullable = false)
    private boolean active;

    /**
     * Балл цели займа для скоринга, от 0 до 1 (CREDIT_POLICY.md §3.2): тип займа и есть цель («На iPhone» —
     * техника, «Последний шанс» — до зарплаты). Задаёт админ в CRM; уходит в credit-policy в событии.
     */
    @Column(name = "purpose_score", nullable = false, precision = 3, scale = 2)
    private BigDecimal purposeScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    /** Нейтральный балл цели для нового типа, если админ его не указал (= DEFAULT в V18). */
    public static final BigDecimal DEFAULT_PURPOSE_SCORE = new BigDecimal("0.50");

    protected LoanType() {
    }

    LoanType(LoanTypeData data, Instant now) {
        this.id = UUID.randomUUID();
        this.createdAt = now;
        this.purposeScore = DEFAULT_PURPOSE_SCORE;
        apply(data, now);
    }

    void apply(LoanTypeData data, Instant now) {
        this.name = data.name();
        this.description = data.description();
        this.minAmount = data.minAmount();
        this.maxAmount = data.maxAmount();
        this.minTermDays = data.minTermDays();
        this.maxTermDays = data.maxTermDays();
        this.dailyRate = data.dailyRate();
        this.modifier = data.modifier();
        this.active = data.active();
        // Форма, не знающая о балле цели, его не стирает: null — «не менять» (при создании — 0.50).
        if (data.purposeScore() != null) {
            this.purposeScore = data.purposeScore();
        }
        this.updatedAt = now;
    }

    void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public int getMinTermDays() {
        return minTermDays;
    }

    public int getMaxTermDays() {
        return maxTermDays;
    }

    public BigDecimal getDailyRate() {
        return dailyRate;
    }

    public BigDecimal getModifier() {
        return modifier;
    }

    public BigDecimal getPurposeScore() {
        return purposeScore;
    }

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }
}
