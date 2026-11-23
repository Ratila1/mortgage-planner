package dev.nosurprise.sms.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSmsRequest(@NotBlank String phone) {}
