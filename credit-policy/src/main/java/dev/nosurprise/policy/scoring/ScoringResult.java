package dev.nosurprise.policy.scoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Строка журнала расчётов (CREDIT_POLICY.md §7.1): вход, расшифровка, версия правил. Не меняется после записи.
 * request_id уникален — по нему повторная доставка запроса возвращает уже посчитанный результат.
 */
@Entity
@Table(name = "scoring_results")
public class ScoringResult {

    @Id
    private UUID id;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScoringStatus status;

    @Column(precision = 5, scale = 4)
    private BigDecimal score;

    private Integer points;

    @Column(name = "rating_class")
    private String ratingClass;

    @Column(precision = 7, scale = 6)
    private BigDecimal pd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScoringDecision decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String reasons;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String breakdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String input;

    @Column(name = "rules_version", nullable = false)
    private int rulesVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ScoringResult() {
    }

    public ScoringResult(UUID requestId, UUID applicationId, UUID userId, ScoringOutcome outcome, String reasons,
                         String breakdown, String input, int rulesVersion, Instant now) {
        this.id = UUID.randomUUID();
        this.requestId = requestId;
        this.applicationId = applicationId;
        this.userId = userId;
        this.status = outcome.status();
        this.score = outcome.score();
        this.points = outcome.points();
        this.ratingClass = outcome.ratingClass();
        this.pd = outcome.pd();
        this.decision = outcome.decision();
        this.reasons = reasons;
        this.breakdown = breakdown;
        this.input = input;
        this.rulesVersion = rulesVersion;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public ScoringStatus getStatus() {
        return status;
    }

    public BigDecimal getScore() {
        return score;
    }

    public Integer getPoints() {
        return points;
    }

    public String getRatingClass() {
        return ratingClass;
    }

    public BigDecimal getPd() {
        return pd;
    }

    public ScoringDecision getDecision() {
        return decision;
    }

    public String getReasons() {
        return reasons;
    }

    public String getBreakdown() {
        return breakdown;
    }

    public String getInput() {
        return input;
    }

    public int getRulesVersion() {
        return rulesVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
