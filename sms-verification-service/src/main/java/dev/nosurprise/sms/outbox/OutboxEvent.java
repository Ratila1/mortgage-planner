package dev.nosurprise.sms.outbox;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event", schema = "sms")
public class OutboxEvent {

    public static final String SMS_DELIVERED = "SMS_DELIVERED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "sms_id", nullable = false)
    private UUID smsId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "locked_by", length = 128)
    private String lockedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public static OutboxEvent smsDelivered(UUID smsId, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.eventType = SMS_DELIVERED;
        event.smsId = smsId;
        event.status = OutboxStatus.NEW;
        event.attempts = 0;
        event.nextAttemptAt = now;
        event.createdAt = now;
        return event;
    }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public UUID getSmsId() { return smsId; }
    public OutboxStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getLockedUntil() { return lockedUntil; }
    public String getLockedBy() { return lockedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
}
