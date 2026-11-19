package dev.nosurprise.smsclient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class VerifyRequest {

    @NotBlank
    @Pattern(regexp = "\\d{6}")
    private String code;

    public VerifyRequest() {}

    public VerifyRequest(String code) {
        this.code = code;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
