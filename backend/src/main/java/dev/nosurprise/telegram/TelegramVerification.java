package dev.nosurprise.telegram;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telegram_verifications")
public class TelegramVerification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "operation_type", nullable = false)
    private String operationType;

    @Column(name = "operation_ref", nullable = false)
    private String operationRef;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TelegramVerification() {}

    public TelegramVerification(UUID id, UUID userId, String operationType, String operationRef,
                                Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.operationType = operationType;
        this.operationRef = operationRef;
        this.status = "PENDING";
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getOperationType() { return operationType; }
    public String getOperationRef() { return operationRef; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
