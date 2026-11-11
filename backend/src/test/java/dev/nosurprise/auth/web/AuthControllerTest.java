package dev.nosurprise.auth.web;

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

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
