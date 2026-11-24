package dev.nosurprise.sms.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SmsResponse(
    UUID id,
    String status,
    int attempts,
    Instant createdAt,
    Instant sentAt,
    Instant expiresAt
) {}
