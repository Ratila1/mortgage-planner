package dev.nosurprise.common.problem;

import dev.nosurprise.calc.service.CalcNotFoundException;
import dev.nosurprise.calculator.InvalidCalculationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Все ветки GlobalExceptionHandler: статус + поле code (§9).
 * Валидация и битый JSON идут через настоящий MVC-пайплайн (@Valid @RequestBody),
 * RuntimeException-ветки — через контроллер, бросающий исключения напрямую.
 * IllegalArgumentException нарочито НЕ ловится: непойманный → 500 INTERNAL_ERROR.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("нарушение @Valid → 400 VALIDATION_FAILED, detail содержит имя поля")
    void validationFailed() throws Exception {
        mockMvc.perform(post("/throw/validation").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price": "-1", "termMonths": 500}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("termMonths")));
    }

    @Test
    @DisplayName("некорректный JSON → 400 INVALID_REQUEST_BODY")
    void invalidRequestBody() throws Exception {
        mockMvc.perform(post("/throw/validation").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("InvalidCalculationException → 400 INVALID_CALC_PARAMS")
    void invalidCalcParams() throws Exception {
        mockMvc.perform(get("/throw/invalid-calc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CALC_PARAMS"))
                .andExpect(jsonPath("$.detail").value("price must be positive"));
    }

    @Test
    @DisplayName("CalcNotFoundException → 404 CALC_NOT_FOUND")
    void calcNotFound() throws Exception {
        mockMvc.perform(get("/throw/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CALC_NOT_FOUND"));
    }

    @Test
    @DisplayName("VerifyTokenInvalidException → 400 VERIFY_TOKEN_INVALID")
    void verifyTokenInvalid() throws Exception {
        mockMvc.perform(get("/throw/verify-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFY_TOKEN_INVALID"))
                .andExpect(jsonPath("$.detail").value("verification token is invalid or expired"));
    }

    @Test
    @DisplayName("IllegalArgumentException и прочие непойманные → 500 INTERNAL_ERROR")
    void unexpectedBecomesInternalError() throws Exception {
        mockMvc.perform(get("/throw/illegal-argument"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        mockMvc.perform(get("/throw/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    record ValidatedBody(
            @NotNull @DecimalMin("0.01") Double price,
            @NotNull @Min(1) @Max(360) Integer termMonths) {
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/throw/validation")
        String validated(@Valid @RequestBody ValidatedBody body) {
            return "ok";
        }

        @GetMapping("/throw/invalid-calc")
        String invalidCalc() {
            throw new InvalidCalculationException("price must be positive");
        }

        @GetMapping("/throw/not-found")
        String notFound() {
            throw new CalcNotFoundException("calculation not found");
        }

        @GetMapping("/throw/verify-token")
        String verifyToken() {
            throw new dev.nosurprise.auth.service.VerifyTokenInvalidException();
        }

        @GetMapping("/throw/illegal-argument")
        String illegalArgument() {
            throw new IllegalArgumentException("should not be mapped to INVALID_CALC_PARAMS");
        }

        @GetMapping("/throw/unexpected")
        String unexpected() {
            throw new IllegalStateException("boom");
        }
    }
}
