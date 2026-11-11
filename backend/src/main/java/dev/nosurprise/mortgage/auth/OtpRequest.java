package dev.nosurprise.mortgage.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record OtpRequest(
        @NotBlank @Email String email,
        OtpCode.OtpPurpose purpose
) {}







