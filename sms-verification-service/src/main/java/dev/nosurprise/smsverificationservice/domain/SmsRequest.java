package dev.nosurprise.smsverificationservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sms_request", schema = "sms")
public class SmsRequest {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "phone", nullable = false, length = 16)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 2)
    private Language language;

    @Column(name = "message", nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SmsStatus status;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 3;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(name = "otp_expires_at", nullable = false)
    private Instant otpExpiresAt;

    @Column(name = "verify_attempts", nullable = false)
    private Integer verifyAttempts = 0;

    @Column(name = "max_verify_attempts", nullable = false)
    private Integer maxVerifyAttempts = 5;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Language getLanguage() { return language; }
    public void setLanguage(Language language) { this.language = language; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public SmsStatus getStatus() { return status; }
    public void setStatus(SmsStatus status) { this.status = status; }

    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }

    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }

    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public String getOtpHash() { return otpHash; }
    public void setOtpHash(String otpHash) { this.otpHash = otpHash; }

    public Instant getOtpExpiresAt() { return otpExpiresAt; }
    public void setOtpExpiresAt(Instant otpExpiresAt) { this.otpExpiresAt = otpExpiresAt; }

    public Integer getVerifyAttempts() { return verifyAttempts; }
    public void setVerifyAttempts(Integer verifyAttempts) { this.verifyAttempts = verifyAttempts; }

    public Integer getMaxVerifyAttempts() { return maxVerifyAttempts; }
    public void setMaxVerifyAttempts(Integer maxVerifyAttempts) { this.maxVerifyAttempts = maxVerifyAttempts; }

    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private String idempotencyKey;
        private String phone;
        private Language language;
        private String message;
        private SmsStatus status;
        private Integer attempts = 0;
        private Integer maxAttempts = 3;
        private Instant nextAttemptAt;
        private String providerMessageId;
        private String lastError;
        private Instant lockedUntil;
        private String lockedBy;
        private Instant sentAt;
        private String otpHash;
        private Instant otpExpiresAt;
        private Integer verifyAttempts = 0;
        private Integer maxVerifyAttempts = 5;
        private Instant verifiedAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder phone(String phone) { this.phone = phone; return this; }
        public Builder language(Language language) { this.language = language; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder status(SmsStatus status) { this.status = status; return this; }
        public Builder attempts(Integer attempts) { this.attempts = attempts; return this; }
        public Builder maxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; return this; }
        public Builder nextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; return this; }
        public Builder providerMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; return this; }
        public Builder lastError(String lastError) { this.lastError = lastError; return this; }
        public Builder lockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; return this; }
        public Builder lockedBy(String lockedBy) { this.lockedBy = lockedBy; return this; }
        public Builder sentAt(Instant sentAt) { this.sentAt = sentAt; return this; }
        public Builder otpHash(String otpHash) { this.otpHash = otpHash; return this; }
        public Builder otpExpiresAt(Instant otpExpiresAt) { this.otpExpiresAt = otpExpiresAt; return this; }
        public Builder verifyAttempts(Integer verifyAttempts) { this.verifyAttempts = verifyAttempts; return this; }
        public Builder maxVerifyAttempts(Integer maxVerifyAttempts) { this.maxVerifyAttempts = maxVerifyAttempts; return this; }
        public Builder verifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; return this; }

        public SmsRequest build() {
            SmsRequest req = new SmsRequest();
            req.id = this.id;
            req.idempotencyKey = this.idempotencyKey;
            req.phone = this.phone;
            req.language = this.language;
            req.message = this.message;
            req.status = this.status;
            req.attempts = this.attempts;
            req.maxAttempts = this.maxAttempts;
            req.nextAttemptAt = this.nextAttemptAt;
            req.providerMessageId = this.providerMessageId;
            req.lastError = this.lastError;
            req.lockedUntil = this.lockedUntil;
            req.lockedBy = this.lockedBy;
            req.sentAt = this.sentAt;
            req.otpHash = this.otpHash;
            req.otpExpiresAt = this.otpExpiresAt;
            req.maxVerifyAttempts = this.maxVerifyAttempts;
            return req;
        }
    }
}