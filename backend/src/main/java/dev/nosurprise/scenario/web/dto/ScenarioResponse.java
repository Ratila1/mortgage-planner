package dev.nosurprise.scenario.web.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ScenarioResponse(
        UUID id,
        String name,
        JsonNode params,
        JsonNode summary,
        String calcId,
        UUID offerId,
        Instant createdAt,
        Instant updatedAt
) {
}