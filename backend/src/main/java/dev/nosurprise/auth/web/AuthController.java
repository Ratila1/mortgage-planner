package dev.nosurprise.auth.web;

import dev.nosurprise.auth.service.RegisterService;
import dev.nosurprise.auth.service.VerificationService;
import dev.nosurprise.auth.web.dto.RegisterRequest;
import dev.nosurprise.auth.web.dto.RegisterResponse;
import dev.nosurprise.auth.web.dto.VerifyEmailResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Публичные auth-эндпоинты контракта §9: POST /api/auth/register и
 * GET /api/auth/verify-email?token=... Логин/JWT/refresh/logout/OAuth — вне этого этапа.
 * Контроллер ходит только в service.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegisterService registerService;
    private final VerificationService verificationService;

    public AuthController(RegisterService registerService, VerificationService verificationService) {
        this.registerService = registerService;
        this.verificationService = verificationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        registerService.register(request);
        return RegisterResponse.generic();
    }

    @GetMapping("/verify-email")
    VerifyEmailResponse verifyEmail(@RequestParam("token") String token) {
        verificationService.verify(token);
        return VerifyEmailResponse.confirmed();
    }
}
