package dev.nosurprise.auth.web;

import dev.nosurprise.auth.service.AuthService;
import dev.nosurprise.auth.service.EmailNotVerifiedException;
import dev.nosurprise.auth.service.InvalidCredentialsException;
import dev.nosurprise.auth.service.InvalidRefreshTokenException;
import dev.nosurprise.auth.service.LoginLinkService;
import dev.nosurprise.auth.service.LoginResult;
import dev.nosurprise.auth.service.OtpInvalidException;
import dev.nosurprise.auth.service.OtpRateLimitExceededException;
import dev.nosurprise.auth.service.RegisterService;
import dev.nosurprise.auth.service.VerificationService;
import dev.nosurprise.auth.service.VerifyTokenInvalidException;
import dev.nosurprise.auth.web.dto.RegisterResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Контракт auth-эндпоинтов (§9): register — всегда одинаковый 202 (существование email
 * не раскрывается), ошибки валидации — 400 VALIDATION_FAILED; verify-email — 200 или
 * 400 VERIFY_TOKEN_INVALID (ProblemDetail от GlobalExceptionHandler).
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false) // SecurityConfig в слайс не входит; фильтры не нужны
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RegisterService registerService;

    @MockitoBean
    VerificationService verificationService;

    @MockitoBean
    AuthService authService;

    @MockitoBean
    dev.nosurprise.auth.service.LoginLinkService loginLinkService;

    @Test
    @DisplayName("POST /api/auth/register: 202 c generic-сообщением, существование email не раскрывается")
    void registerAccepted() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Sup3rSecret!\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(RegisterResponse.GENERIC));

        verify(registerService).register(new dev.nosurprise.auth.web.dto.RegisterRequest(
                "user@example.com", "Sup3rSecret!"));
    }

    @Test
    @DisplayName("POST /api/auth/register: короткий пароль → 400 VALIDATION_FAILED")
    void registerShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(registerService);
    }

    @Test
    @DisplayName("POST /api/auth/register: невалидный email → 400 VALIDATION_FAILED")
    void registerInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"Sup3rSecret!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(registerService);
    }

    @Test
    @DisplayName("POST /api/auth/register: пустой email → 400 VALIDATION_FAILED")
    void registerBlankEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"Sup3rSecret!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/auth/verify-email: валидный токен → 200, access в теле + refresh-cookie (сразу вошёл)")
    void verifyEmailOk() throws Exception {
        UUID userId = UUID.randomUUID();
        when(verificationService.consume("token-1", "127.0.0.1"))
                .thenReturn(new LoginResult("access-jwt", 900, userId + ".opaque"));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"token-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().value("refresh_token", userId + ".opaque"));

        verify(verificationService).consume("token-1", "127.0.0.1");
    }

    @Test
    @DisplayName("POST /api/auth/verify-email: истёкший/использованный токен → 400 VERIFY_TOKEN_INVALID")
    void verifyEmailInvalidToken() throws Exception {
        doThrow(new VerifyTokenInvalidException()).when(verificationService).consume("expired", "127.0.0.1");

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"expired\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFY_TOKEN_INVALID"))
                .andExpect(jsonPath("$.detail").value("verification token is invalid or expired"));
    }

    @Test
    @DisplayName("POST /api/auth/verify-email без токена → 400 VALIDATION_FAILED")
    void verifyEmailMissingToken() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/auth/login-link: 202, ссылка запрошена")
    void requestLoginLinkAccepted() throws Exception {
        mockMvc.perform(post("/api/auth/login-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isAccepted());

        verify(loginLinkService).requestLoginLink("user@example.com");
    }

    @Test
    @DisplayName("POST /api/auth/login-link: невалидный email → 400 VALIDATION_FAILED")
    void requestLoginLinkInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/auth/login-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(loginLinkService);
    }

    @Test
    @DisplayName("POST /api/auth/login-link/consume: валидный токен → 200, access + refresh-cookie")
    void consumeLoginLinkOk() throws Exception {
        UUID userId = UUID.randomUUID();
        when(loginLinkService.consumeLoginLink("tok", "127.0.0.1"))
                .thenReturn(new LoginResult("access-jwt", 900, userId + ".opaque"));

        mockMvc.perform(post("/api/auth/login-link/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().value("refresh_token", userId + ".opaque"));

        verify(loginLinkService).consumeLoginLink("tok", "127.0.0.1");
    }

    @Test
    @DisplayName("POST /api/auth/login-link/consume: битый токен → 400 OTP_INVALID")
    void consumeLoginLinkInvalidToken() throws Exception {
        when(loginLinkService.consumeLoginLink("bad", "127.0.0.1"))
                .thenThrow(new OtpInvalidException("invalid login link"));

        mockMvc.perform(post("/api/auth/login-link/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    @Test
    @DisplayName("POST /api/auth/login-link: слишком много запросов → 429 RATE_LIMITED")
    void requestLoginLinkRateLimited() throws Exception {
        doThrow(new OtpRateLimitExceededException("too many login link requests"))
                .when(loginLinkService).requestLoginLink("user@example.com");

        mockMvc.perform(post("/api/auth/login-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("POST /api/auth/login: успех → access в теле (Bearer) + refresh в httpOnly cookie")
    void loginSuccess() throws Exception {
        when(authService.login("user@example.com", "Sup3rSecret!", "127.0.0.1"))
                .thenReturn(new LoginResult("access-jwt", 900, "uid.opaque"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Sup3rSecret!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().value("refresh_token", "uid.opaque"))
                .andExpect(cookie().maxAge("refresh_token", -1))
                .andExpect(cookie().path("refresh_token", "/api/auth"))
                .andExpect(cookie().sameSite("refresh_token", "Lax"));
    }

    @Test
    @DisplayName("POST /api/auth/login: Secure-флаг добавляется только при https")
    void loginSecureCookieOverHttps() throws Exception {
        when(authService.login("user@example.com", "Sup3rSecret!", "127.0.0.1"))
                .thenReturn(new LoginResult("access-jwt", 900, "uid.opaque"));

        mockMvc.perform(post("/api/auth/login").secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Sup3rSecret!\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().secure("refresh_token", true));
    }

    @Test
    @DisplayName("POST /api/auth/login: неверный пароль → 401 INVALID_CREDENTIALS")
    void loginWrongPassword() throws Exception {
        when(authService.login("user@example.com", "Wr0ngPass!!", "127.0.0.1"))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Wr0ngPass!!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("POST /api/auth/login: неизвестный email → 401 INVALID_CREDENTIALS (тот же код, что неверный пароль)")
    void loginUnknownEmail() throws Exception {
        when(authService.login("ghost@example.com", "Sup3rSecret!", "127.0.0.1"))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost@example.com\",\"password\":\"Sup3rSecret!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("POST /api/auth/login: email неподтверждён → 403 EMAIL_NOT_VERIFIED")
    void loginUnverifiedEmail() throws Exception {
        when(authService.login("user@example.com", "Sup3rSecret!", "127.0.0.1"))
                .thenThrow(new EmailNotVerifiedException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Sup3rSecret!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("POST /api/auth/login: невалидное тело → 400 VALIDATION_FAILED")
    void loginValidationFailed() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("POST /api/auth/refresh: валидный cookie → 200, ротация (новый Set-Cookie)")
    void refreshRotatesCookie() throws Exception {
        when(authService.refresh("uid.old", "127.0.0.1"))
                .thenReturn(new LoginResult("access-jwt", 900, "uid.new"));

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", "uid.old")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(cookie().value("refresh_token", "uid.new"))
                .andExpect(cookie().httpOnly("refresh_token", true));
    }

    @Test
    @DisplayName("POST /api/auth/refresh: без cookie / невалидный → 401 INVALID_REFRESH_TOKEN")
    void refreshInvalidToken() throws Exception {
        when(authService.refresh(null, "127.0.0.1")).thenThrow(new InvalidRefreshTokenException());

        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("POST /api/auth/logout: 204, refresh-токен отозван, cookie очищается (Max-Age=0)")
    void logoutRevokesAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", "uid.old")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("refresh_token", 0));

        verify(authService).logout("uid.old");
    }

    @Test
    @DisplayName("POST /api/auth/logout без cookie → 204 (идемпотентно)")
    void logoutWithoutCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());

        verify(authService).logout(null);
    }
}
