package com.bank.mortgage.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("otp_codes")
public record OtpCode(
        @Id Long id,
        String email,
        String codeHash,
        OtpPurpose purpose,
        Instant expiresAt,
        boolean used,
        Instant createdAt
) {
    public enum OtpPurpose {
        LOGIN,
        REGISTER_CONFIRM,
        EMAIL_CHANGE
    }

    public static OtpCodeBuilder builder() {
        return new OtpCodeBuilder();
    }

    public static class OtpCodeBuilder {
        private Long id;
        private String email;
        private String codeHash;
        private OtpPurpose purpose;
        private Instant expiresAt;
        private boolean used = false;
        private Instant createdAt = Instant.now();

        public OtpCodeBuilder id(Long id) { this.id = id; return this; }
        public OtpCodeBuilder email(String email) { this.email = email; return this; }
        public OtpCodeBuilder codeHash(String codeHash) { this.codeHash = codeHash; return this; }
        public OtpCodeBuilder purpose(OtpPurpose purpose) { this.purpose = purpose; return this; }
        public OtpCodeBuilder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
        public OtpCodeBuilder used(boolean used) { this.used = used; return this; }
        public OtpCodeBuilder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public OtpCode build() {
            return new OtpCode(id, email, codeHash, purpose, expiresAt, used, createdAt);
        }
    }
}