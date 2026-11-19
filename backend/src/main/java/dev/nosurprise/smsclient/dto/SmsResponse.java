package dev.nosurprise.smsclient.dto;

import java.time.Instant;
import java.util.UUID;

public class SmsResponse {

    private UUID id;
    private String status;
    private int attempts;
    private Instant createdAt;
    private Instant sentAt;
    private Instant expiresAt;

    public SmsResponse() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private String status;
        private int attempts;
        private Instant createdAt;
        private Instant sentAt;
        private Instant expiresAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder attempts(int attempts) { this.attempts = attempts; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder sentAt(Instant sentAt) { this.sentAt = sentAt; return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }

        public SmsResponse build() {
            SmsResponse dto = new SmsResponse();
            dto.id = this.id;
            dto.status = this.status;
            dto.attempts = this.attempts;
            dto.createdAt = this.createdAt;
            dto.sentAt = this.sentAt;
            dto.expiresAt = this.expiresAt;
            return dto;
        }
    }
}
