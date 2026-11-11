package dev.nosurprise.calc.web;

import dev.nosurprise.calc.service.CalcNotFoundException;
import dev.nosurprise.calc.service.CalculatorService;
import dev.nosurprise.calc.web.dto.CalcResponse;
import dev.nosurprise.calc.web.dto.ScheduleRowResponse;
import dev.nosurprise.calc.web.dto.SavingsResponse;
import dev.nosurprise.calculator.InvalidCalculationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Контракт HTTP API (§9): деньги — строки, ошибки — ProblemDetail с полем code.
 */
@WebMvcTest(CalculatorController.class)
@AutoConfigureMockMvc(addFilters = false) // SecurityConfig в слайс не входит; фильтры не нужны
class CalculatorControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CalculatorService service;

    private static final String VALID_BODY = """
            {
              "price": "1000000.00",
              "downPayment": "0",
              "termMonths": 12,
              "annualRate": "12",
              "type": "ANNUITY",
              "earlyRepayments": []
            }
            """;

    private CalcResponse response(String calcId) {
        List<ScheduleRowResponse> schedule = List.of(new ScheduleRowResponse(
                1, "88848.79", "78848.79", "10000.00", "0.00", "921151.21"));
        return new CalcResponse(calcId, "1000000.00", "88848.79", "66185.45", "1066185.45",
                12, new SavingsResponse("0.00", 0), schedule);
    }

    @Test
    @DisplayName("POST /api/calc: 200, calcId и деньги строками")
    void postCalcReturnsStringifiedMoney() throws Exception {
        when(service.calculate(any())).thenReturn(response("665f0f0f1e1e1e1e1e1e1e1e"));

        mockMvc.perform(post("/api/calc").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calcId").value("665f0f0f1e1e1e1e1e1e1e1e"))
                .andExpect(jsonPath("$.loanAmount").value("1000000.00"))
                .andExpect(jsonPath("$.monthlyPayment").value("88848.79"))
                .andExpect(jsonPath("$.overpayment").value("66185.45"))
                .andExpect(jsonPath("$.totalPaid").value("1066185.45"))
                .andExpect(jsonPath("$.termMonths").value(12))
                .andExpect(jsonPath("$.schedule[0].payment").value("88848.79"))
                .andExpect(jsonPath("$.schedule[0].interest").value("10000.00"));
    }

    @Test
    @DisplayName("POST /api/calc: нарушение валидации → 400 VALIDATION_FAILED")
    void postInvalidBodyValidationFailed() throws Exception {
        String invalid = """
                {
                  "price": "1000000.00",
                  "downPayment": "0",
                  "termMonths": 500,
                  "annualRate": "12",
                  "type": "ANNUITY"
                }
                """;

        mockMvc.perform(post("/api/calc").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.termMonths").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/calc: некорректный JSON → 400 INVALID_REQUEST_BODY")
    void postMalformedJson() throws Exception {
        mockMvc.perform(post("/api/calc").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("POST /api/calc: неизвестное значение enum → 400 INVALID_REQUEST_BODY")
    void postUnknownEnumValue() throws Exception {
        String invalid = VALID_BODY.replace("ANNUITY", "SOMETHING_ELSE");

        mockMvc.perform(post("/api/calc").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("POST /api/calc: нарушение правил расчёта → 400 INVALID_CALC_PARAMS")
    void postInvalidCalcParams() throws Exception {
        when(service.calculate(any())).thenThrow(new InvalidCalculationException("price must be positive"));

        mockMvc.perform(post("/api/calc").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CALC_PARAMS"));
    }

    @Test
    @DisplayName("GET /api/calc/{calcId}: 200 с сохранённым расчётом")
    void getCalc() throws Exception {
        when(service.get("665f0f0f1e1e1e1e1e1e1e1e")).thenReturn(response("665f0f0f1e1e1e1e1e1e1e1e"));

        mockMvc.perform(get("/api/calc/665f0f0f1e1e1e1e1e1e1e1e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calcId").value("665f0f0f1e1e1e1e1e1e1e1e"))
                .andExpect(jsonPath("$.schedule[0].balance").value("921151.21"));
    }

    @Test
    @DisplayName("GET /api/calc/{calcId}: неизвестный calcId → 404 CALC_NOT_FOUND")
    void getUnknownCalcId() throws Exception {
        when(service.get("aaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenThrow(new CalcNotFoundException("calculation not found"));

        mockMvc.perform(get("/api/calc/aaaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CALC_NOT_FOUND"));
    }
}
