package dev.nosurprise.sms.telegram.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ConfirmCodeRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
        String code
) {}
