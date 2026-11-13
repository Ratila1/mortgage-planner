package dev.nosurprise.auth.web;

import dev.nosurprise.auth.service.AuthService;
import dev.nosurprise.auth.service.LoginResult;
import dev.nosurprise.auth.service.OtpService;
import dev.nosurprise.auth.service.RegisterService;
import dev.nosurprise.auth.service.VerificationService;
import dev.nosurprise.auth.web.dto.LoginRequest;
import dev.nosurprise.auth.web.dto.LoginResponse;
import dev.nosurprise.auth.web.dto.OtpRequest;
import dev.nosurprise.auth.web.dto.OtpVerifyRequest;
import dev.nosurprise.auth.web.dto.RegisterRequest;
import dev.nosurprise.auth.web.dto.RegisterResponse;
import dev.nosurprise.auth.web.dto.VerifyEmailResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.RestController;

/**
 * Публичные auth-эндпоинты контракта §9: register, verify-email, логин (access в теле +
 * refresh в httpOnly-cookie, §7), ротация refresh и logout. Cookie: {userId}.{opaqueToken},
 * HttpOnly, SameSite=Lax, Path=/api/auth, Secure только при https. Контроллер ходит только
 * в service. OAuth, logout-all и прочие эндпоинты вне текущего контракта — не реализованы.
 */
@RestController
@RequestMapping("/api/auth")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class AuthController {

    public static final String REFRESH_COOKIE = "refresh_token";

    private final RegisterService registerService;
    private final VerificationService verificationService;
    private final AuthService authService;
    private final OtpService otpService;

    public AuthController(RegisterService registerService, VerificationService verificationService,
                          AuthService authService) {
        this(registerService, verificationService, authService, null);
    }

    @Autowired
    public AuthController(RegisterService registerService, VerificationService verificationService,
                          AuthService authService, OtpService otpService) {
        this.registerService = registerService;
        this.verificationService = verificationService;
        this.authService = authService;
        this.otpService = otpService;
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

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                        HttpServletRequest httpRequest) {
        LoginResult result = authService.login(request.email(), request.password(),
                httpRequest.getRemoteAddr());
        return ResponseEntity.ok()
                .header("Set-Cookie", refreshCookie(result.refreshTokenCookie(), httpRequest))
                .body(new LoginResponse(result.accessToken(),
                        LoginResponse.TOKEN_TYPE, result.expiresIn()));
    }

    @PostMapping("/refresh")
    ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshCookie,
            HttpServletRequest httpRequest) {
        LoginResult result = authService.refresh(refreshCookie, httpRequest.getRemoteAddr());
        return ResponseEntity.ok()
                .header("Set-Cookie", refreshCookie(result.refreshTokenCookie(), httpRequest))
                .body(new LoginResponse(result.accessToken(),
                        LoginResponse.TOKEN_TYPE, result.expiresIn()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshCookie,
            HttpServletRequest httpRequest) {
        authService.logout(refreshCookie);
        return ResponseEntity.noContent()
                .header("Set-Cookie", refreshCookie("", httpRequest)
                        + "; Max-Age=0")
                .build();
    }

    @PostMapping("/otp/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestOtp(@Valid @RequestBody OtpRequest request) {
        if (otpService == null) {
            throw new IllegalStateException("otp service is not configured");
        }
        otpService.requestOtp(request.email());
    }

    @PostMapping("/otp/verify")
    ResponseEntity<LoginResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request,
                                           HttpServletRequest httpRequest) {
        if (otpService == null) {
            throw new IllegalStateException("otp service is not configured");
        }
        LoginResult result = otpService.verifyOtp(request.email(), request.code(),
                httpRequest.getRemoteAddr());
        return ResponseEntity.ok()
                .header("Set-Cookie", refreshCookie(result.refreshTokenCookie(), httpRequest))
                .body(new LoginResponse(result.accessToken(), LoginResponse.TOKEN_TYPE, result.expiresIn()));
    }

    /** Refresh-cookie (§7): HttpOnly, SameSite=Lax, Path=/api/auth, Secure только при https. */
    static String refreshCookie(String value, HttpServletRequest request) {
        StringBuilder cookie = new StringBuilder(REFRESH_COOKIE).append("=").append(value);
        cookie.append("; HttpOnly; SameSite=Lax; Path=/api/auth");
        if (request.isSecure()) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }
}
