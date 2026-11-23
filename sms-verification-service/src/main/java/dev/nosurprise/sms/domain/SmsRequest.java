package dev.nosurprise.sms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

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

    @Column(name = "sent_at")
    private Instant sentAt;

    /** Срок жизни OTP: ответ API и перевод в EXPIRED считаются от него, а не от created_at + конфиг. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private String idempotencyKey;
        private String phone;
        private Language language;
        private String message;
        private SmsStatus status;
        private Integer attempts = 0;
        private Instant sentAt;
        private Instant expiresAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder phone(String phone) { this.phone = phone; return this; }
        public Builder language(Language language) { this.language = language; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder status(SmsStatus status) { this.status = status; return this; }
        public Builder attempts(Integer attempts) { this.attempts = attempts; return this; }
        public Builder sentAt(Instant sentAt) { this.sentAt = sentAt; return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }

        public SmsRequest build() {
            SmsRequest req = new SmsRequest();
            req.id = this.id;
            req.idempotencyKey = this.idempotencyKey;
            req.phone = this.phone;
            req.language = this.language;
            req.message = this.message;
            req.status = this.status;
            req.attempts = this.attempts;
            req.sentAt = this.sentAt;
            req.expiresAt = this.expiresAt;
            return req;
        }
    }
}
