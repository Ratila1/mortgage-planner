package dev.nosurprise.smsverificationservice.api;

import dev.nosurprise.smsverificationservice.api.dto.CreateSmsRequest;
import dev.nosurprise.smsverificationservice.api.dto.SmsResponse;
import dev.nosurprise.smsverificationservice.service.SmsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sms")
public class SmsController {

    private final SmsService smsService;

    public SmsController(SmsService smsService) {
        this.smsService = smsService;
    }

    @PostMapping
    public ResponseEntity<SmsResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateSmsRequest request,
            HttpServletRequest httpRequest
    ) {
        String key = idempotencyKey != null ? idempotencyKey : httpRequest.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Missing Idempotency-Key header");
        }

        var result = smsService.create(key, request.getPhone());
        SmsResponse response = SmsResponse.builder()
            .id(result.request().getId())
            .status(result.request().getStatus().name())
            .attempts(result.request().getAttempts())
            .createdAt(result.request().getCreatedAt())
            .sentAt(result.request().getSentAt())
            .expiresAt(result.request().getOtpExpiresAt())
            .build();

        return ResponseEntity
            .status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
            .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SmsResponse> get(@PathVariable UUID id) {
        return smsService.get(id)
            .map(req -> ResponseEntity.ok(SmsResponse.builder()
                .id(req.getId())
                .status(req.getStatus().name())
                .attempts(req.getAttempts())
                .createdAt(req.getCreatedAt())
                .sentAt(req.getSentAt())
                .expiresAt(req.getOtpExpiresAt())
                .build()))
            .orElse(ResponseEntity.notFound().build());
    }
}