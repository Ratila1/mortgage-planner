package dev.nosurprise.notificationservice.web;

import dev.nosurprise.notificationservice.dto.ConfirmCodeRequest;
import dev.nosurprise.notificationservice.dto.VerificationStatusResponse;
import dev.nosurprise.notificationservice.service.VerificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/verify")
public class VerificationController {
    private final VerificationService service;

    public VerificationController(VerificationService service) { this.service = service; }

    @GetMapping("/{id}/status")
    public VerificationStatusResponse status(@PathVariable UUID id) {
        return new VerificationStatusResponse(id.toString(), service.status(id), true);
    }

    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                       @RequestBody ConfirmCodeRequest req) {
        UUID userId = UUID.fromString(jwt.getSubject());
        boolean ok = service.confirm(id, userId, req);
        return Map.of("verified", ok);
    }
}