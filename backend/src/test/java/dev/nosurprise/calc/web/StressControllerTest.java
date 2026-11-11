package dev.nosurprise.calc.web;

import dev.nosurprise.calc.service.StressService;
import dev.nosurprise.calc.web.dto.StressResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StressController.class)
@AutoConfigureMockMvc(addFilters = false)
class StressControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    StressService service;

    @Test
    @DisplayName("POST /api/calc/stress: 200, delta и строки")
    void stressOk() throws Exception {
        when(service.stress(any())).thenReturn(List.of(new StressResponse(new java.math.BigDecimal("2"), "100", "10")));

        mockMvc.perform(post("/api/calc/stress").contentType(MediaType.APPLICATION_JSON).content("""
                {
                  "price": "1000000.00",
                  "downPayment": "0",
                  "termMonths": 12,
                  "annualRate": "12",
                  "type": "ANNUITY",
                  "earlyRepayments": [],
                  "delta": 2
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].delta").value(2))
                .andExpect(jsonPath("$[0].monthlyPayment").value("100"))
                .andExpect(jsonPath("$[0].overpayment").value("10"));
    }

    @Test
    @DisplayName("POST /api/calc/stress: невалидное тело -> 400")
    void stressInvalid() throws Exception {
        mockMvc.perform(post("/api/calc/stress").contentType(MediaType.APPLICATION_JSON).content("""
                {
                  "price": "1000000.00",
                  "termMonths": 12,
                  "annualRate": "12",
                  "type": "ANNUITY"
                }
                """))
                .andExpect(status().isBadRequest());
    }
}