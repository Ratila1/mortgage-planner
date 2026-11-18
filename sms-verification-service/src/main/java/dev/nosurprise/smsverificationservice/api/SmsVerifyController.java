package dev.nosurprise.smsverificationservice.api;

import dev.nosurprise.smsverificationservice.api.dto.VerifyRequest;
import dev.nosurprise.smsverificationservice.api.dto.VerifyResponse;
import dev.nosurprise.smsverificationservice.service.SmsVerifyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sms")
public class SmsVerifyController {

    private final SmsVerifyService verifyService;

    public SmsVerifyController(SmsVerifyService verifyService) {
        this.verifyService = verifyService;
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<VerifyResponse> verify(
            @PathVariable UUID id,
            @Valid @RequestBody VerifyRequest request,
            @AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt
    ) {
        var result = verifyService.verify(id, request.getCode());
        return ResponseEntity.ok(VerifyResponse.builder()
            .verified(result.verified())
            .reason(result.reason())
            .build());
    }
}