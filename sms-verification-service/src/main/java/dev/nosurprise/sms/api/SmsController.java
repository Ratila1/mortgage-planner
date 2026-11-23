package dev.nosurprise.sms.api;

import dev.nosurprise.sms.api.dto.CreateSmsRequest;
import dev.nosurprise.sms.api.dto.SmsResponse;
import dev.nosurprise.sms.api.dto.VerifyRequest;
import dev.nosurprise.sms.api.dto.VerifyResponse;
import dev.nosurprise.sms.service.SmsService;
import dev.nosurprise.sms.service.SmsVerifyService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Все SMS-эндпоинты сервиса в одном контроллере: создание, чтение статуса и верификация кода.
 * Любой из них требует service-JWT (sub=sms-client) — пользовательский токен сюда не пускается.
 */
@RestController
@RequestMapping("/api/v1/sms")
public class SmsController {

    private final SmsService smsService;
    private final SmsVerifyService verifyService;

    public SmsController(SmsService smsService, SmsVerifyService verifyService) {
        this.smsService = smsService;
        this.verifyService = verifyService;
    }

    @PostMapping
    public ResponseEntity<SmsResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateSmsRequest request
    ) {
        try {
            SmsService.CreateResult result = smsService.create(idempotencyKey, request.phone());
            return ResponseEntity
                .status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(smsService.toResponse(result.request()));
        } catch (DataIntegrityViolationException duplicate) {
            // Гонка по Idempotency-Key: параллельный запрос уже вставил строку. Отдаём её как обычный
            // повтор (200); если нарушение было не по ключу — пробрасываем исходную ошибку.
            return smsService.findDuplicate(idempotencyKey, request.phone())
                .map(duplicateRequest -> ResponseEntity.ok(smsService.toResponse(duplicateRequest)))
                .orElseThrow(() -> duplicate);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<SmsResponse> get(@PathVariable UUID id) {
        return smsService.get(id)
            .map(smsService::toResponse)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<VerifyResponse> verify(
            @PathVariable UUID id,
            @Valid @RequestBody VerifyRequest request
    ) {
        var result = verifyService.verify(id, request.code());
        return ResponseEntity.ok(new VerifyResponse(result.verified(), result.reason()));
    }
}
