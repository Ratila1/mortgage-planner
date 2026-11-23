package dev.nosurprise.sms.telegram.web;

import dev.nosurprise.sms.domain.SmsStatus;
import dev.nosurprise.sms.telegram.dto.ConfirmCodeRequest;
import dev.nosurprise.sms.telegram.dto.VerificationStatusResponse;
import dev.nosurprise.sms.telegram.service.VerificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
        SmsStatus status = service.status(id, userId(jwt));
        boolean codeSent = status == SmsStatus.DELIVERED || status == SmsStatus.VERIFIED;
        return new VerificationStatusResponse(id.toString(), status.name(), codeSent);
    }

    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable UUID id,
                                       @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ConfirmCodeRequest request) {
        return Map.of("verified", service.confirm(id, userId(jwt), request.code()));
    }

    /** Subject пользовательского JWT — id пользователя; всё остальное (сервисный токен, e-mail) сюда не пускаем. */
    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject must be a user id");
        }
    }
}
