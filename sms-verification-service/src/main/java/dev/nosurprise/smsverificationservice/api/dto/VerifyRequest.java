package dev.nosurprise.smsverificationservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class VerifyRequest {

    @NotBlank
    @Pattern(regexp = "\\d{6}")
    private String code;

    public VerifyRequest() {}

    public VerifyRequest(String code) { this.code = code; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String code;

        public Builder code(String code) { this.code = code; return this; }
        public VerifyRequest build() {
            VerifyRequest dto = new VerifyRequest();
            dto.code = this.code;
            return dto;
        }
    }
}