package dev.nosurprise.notification.web.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        JsonNode payload,
        boolean read,
        Instant createdAt
) {
}