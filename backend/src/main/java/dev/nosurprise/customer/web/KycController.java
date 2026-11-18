package dev.nosurprise.customer.web;

import dev.nosurprise.customer.kyc.KycService;
import dev.nosurprise.customer.kyc.UnsupportedFileTypeException;
import dev.nosurprise.customer.web.dto.KycMetadataResponse;
import dev.nosurprise.user.UserService;
import io.micrometer.core.annotation.Timed;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Загрузка фото паспорта (KYC, MICROLOANS.md §3.11). Имя файла от клиента не используется (§5).
 * Лимит размера срабатывает трижды: nginx (client_max_body_size), Spring multipart и проверка в сервисе.
 */
@RestController
@RequestMapping("/api/me/kyc")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class KycController {

    private final KycService kycService;
    private final UserService userService;

    public KycController(KycService kycService, UserService userService) {
        this.kycService = kycService;
        this.userService = userService;
    }

    @PostMapping(value = "/passport", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<KycMetadataResponse> upload(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        UUID userId = UUID.fromString(jwt.getSubject());
        userService.assertNotBlocked(userId);
        if (file == null || file.isEmpty()) {
            throw new UnsupportedFileTypeException();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(KycMetadataResponse.from(kycService.uploadPassport(userId, file.getBytes())));
    }

    /** 204, если фото ещё не загружено. */
    @GetMapping("/passport")
    ResponseEntity<KycMetadataResponse> metadata(@AuthenticationPrincipal Jwt jwt) {
        return kycService.latestPassport(UUID.fromString(jwt.getSubject()))
                .map(metadata -> ResponseEntity.ok(KycMetadataResponse.from(metadata)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
