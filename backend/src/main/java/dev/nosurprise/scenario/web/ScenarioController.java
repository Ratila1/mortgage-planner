package dev.nosurprise.scenario.web;

import dev.nosurprise.scenario.ScenarioService;
import dev.nosurprise.scenario.web.dto.CreateScenarioRequest;
import dev.nosurprise.scenario.web.dto.ScenarioResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/scenarios")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class ScenarioController {

    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping
    public List<ScenarioResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return scenarioService.list(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping
    public org.springframework.http.ResponseEntity<ScenarioResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateScenarioRequest request) {
        ScenarioResponse response = scenarioService.create(UUID.fromString(jwt.getSubject()), request);
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    public org.springframework.http.ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        scenarioService.delete(UUID.fromString(jwt.getSubject()), id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/compare")
    public List<ScenarioResponse> compare(@AuthenticationPrincipal Jwt jwt, @RequestParam("ids") String ids) {
        List<UUID> idList = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .toList();
        return scenarioService.compare(UUID.fromString(jwt.getSubject()), idList);
    }
}