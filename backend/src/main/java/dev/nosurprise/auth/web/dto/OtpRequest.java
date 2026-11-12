package dev.nosurprise.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record OtpRequest(@NotBlank @Email String email) {
}