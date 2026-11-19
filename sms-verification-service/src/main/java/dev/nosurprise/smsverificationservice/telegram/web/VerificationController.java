package dev.nosurprise.smsverificationservice.telegram.web;

import dev.nosurprise.smsverificationservice.telegram.dto.ConfirmCodeRequest;
import dev.nosurprise.smsverificationservice.telegram.dto.VerificationStatusResponse;
import dev.nosurprise.smsverificationservice.telegram.service.VerificationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/verify")
public class VerificationController {

    private final VerificationService service;

    public VerificationController(VerificationService service) {
        this.service = service;
    }

    @GetMapping("/{id}/status")
    public VerificationStatusResponse status(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String status = service.status(id, userId);
        return new VerificationStatusResponse(id.toString(), status, "SENT".equals(status));
    }

    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable UUID id,
                                       @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ConfirmCodeRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return Map.of("verified", service.confirm(id, userId, request.code()));
    }
}
