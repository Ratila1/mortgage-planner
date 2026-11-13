package dev.nosurprise.scenario.web.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateScenarioRequest(
        @NotBlank String name,
        @NotNull JsonNode params,
        UUID offerId
) {
}