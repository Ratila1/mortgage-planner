package dev.nosurprise.loan.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка журнала заявки (MICROLOANS.md §3.14). id — bigserial: только растёт, поэтому max(id)
 * служит версией документа кредитной истории в Mongo (§4.9). Пишется только через {@link LoanEventRecorder}.
 */
@Entity
@Table(name = "loan_events")
public class LoanEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanEventType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private LoanStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private LoanStatus toStatus;

    @Column(name = "actor_id")
    private UUID actorId;

    /** Без персональных данных (§5): только технические детали вроде номера попытки. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LoanEvent() {
    }

    LoanEvent(UUID applicationId, LoanEventType type, LoanStatus fromStatus, LoanStatus toStatus, UUID actorId,
              String details, Instant occurredAt) {
        this.applicationId = applicationId;
        this.type = type;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.details = details;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public LoanEventType getType() {
        return type;
    }

    public LoanStatus getFromStatus() {
        return fromStatus;
    }

    public LoanStatus getToStatus() {
        return toStatus;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getDetails() {
        return details;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
