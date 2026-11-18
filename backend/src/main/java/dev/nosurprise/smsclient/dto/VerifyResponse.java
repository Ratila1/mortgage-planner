package dev.nosurprise.smsclient.dto;

import java.time.Instant;
import java.util.UUID;

public class VerifyResponse {

    private boolean verified;
    private String reason;

    public VerifyResponse() {}

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean verified;
        private String reason;

        public Builder verified(boolean verified) { this.verified = verified; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }

        public VerifyResponse build() {
            VerifyResponse dto = new VerifyResponse();
            dto.verified = this.verified;
            dto.reason = this.reason;
            return dto;
        }
    }
}
