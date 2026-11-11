package dev.nosurprise.auth.web;

import dev.nosurprise.auth.service.AuthService;
import dev.nosurprise.auth.service.EmailNotVerifiedException;
import dev.nosurprise.auth.service.InvalidCredentialsException;
import dev.nosurprise.auth.service.InvalidRefreshTokenException;
import dev.nosurprise.auth.service.LoginResult;
import dev.nosurprise.auth.service.RegisterService;
import dev.nosurprise.auth.service.VerificationService;
import dev.nosurprise.auth.service.VerifyTokenInvalidException;
import dev.nosurprise.auth.web.dto.RegisterResponse;
import dev.nosurprise.auth.web.dto.VerifyEmailResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @DisplayName("GET /api/auth/verify-email?token=: валидный токен → 200")
    void verifyEmailOk() throws Exception {
        doNothing().when(verificationService).verify("token-1");

        mockMvc.perform(get("/api/auth/verify-email").queryParam("token", "token-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(VerifyEmailResponse.confirmed().message()));

        verify(verificationService).verify("token-1");
    }

    @Test
    @DisplayName("GET /api/auth/verify-email?token=: истёкший/неизвестный токен → 400 VERIFY_TOKEN_INVALID")
    void verifyEmailInvalidToken() throws Exception {
        doThrow(new VerifyTokenInvalidException()).when(verificationService).verify("expired");

        mockMvc.perform(get("/api/auth/verify-email").queryParam("token", "expired"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFY_TOKEN_INVALID"))
                .andExpect(jsonPath("$.detail").value("verification token is invalid or expired"));
    }

    @Test
    @DisplayName("GET /api/auth/verify-email без токена → 400 VALIDATION_FAILED (missing parameter)")
    void verifyEmailMissingToken() throws Exception {
        mockMvc.perform(get("/api/auth/verify-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
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
