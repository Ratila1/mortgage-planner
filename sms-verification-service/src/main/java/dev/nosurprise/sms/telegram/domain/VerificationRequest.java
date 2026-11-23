package dev.nosurprise.sms.telegram.domain;

import dev.nosurprise.sms.domain.SmsStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_requests", schema = "sms")
public class VerificationRequest {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "operation_type", nullable = false)
    private String operationType;

    @Column(name = "operation_ref", nullable = false)
    private String operationRef;

    @Column(name = "sms_id")
    private UUID smsId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SmsStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected VerificationRequest() {}

    public VerificationRequest(UUID id, UUID userId, String operationType, String operationRef,
                               SmsStatus status, Instant createdAt, Instant expiresAt, UUID smsId) {
        this.id = id;
        this.userId = userId;
        this.operationType = operationType;
        this.operationRef = operationRef;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.smsId = smsId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getOperationType() { return operationType; }
    public String getOperationRef() { return operationRef; }
    public UUID getSmsId() { return smsId; }
    public SmsStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setStatus(SmsStatus status) { this.status = status; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
