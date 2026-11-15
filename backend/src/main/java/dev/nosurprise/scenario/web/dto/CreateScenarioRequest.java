package dev.nosurprise.scenario.web.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateScenarioRequest(
        @NotBlank
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,
        @NotNull JsonNode params,
        UUID offerId
) {
}