package dev.nosurprise.smsverificationservice.telegram.dto;

import jakarta.validation.constraints.Pattern;

public record ConfirmCodeRequest(
        @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
        String code
) {}
