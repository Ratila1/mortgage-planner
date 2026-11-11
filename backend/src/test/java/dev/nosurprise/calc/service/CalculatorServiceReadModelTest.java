package dev.nosurprise.calc.service;

import dev.nosurprise.calc.repository.CalculationDocument;
import dev.nosurprise.calc.repository.CalculationRepository;
import dev.nosurprise.calc.cache.CalcCache;
import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.LoanSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CalculatorServiceReadModelTest {

    @Test
    @DisplayName("getCalculation возвращает read model")
    void found() {
        CalculationRepository repository = mock(CalculationRepository.class);
        CalcCache cache = mock(CalcCache.class);
        CalculatorService service = new CalculatorService(repository, cache);

        CalculationDocument doc = new CalculationDocument("507f1f77bcf86cd799439011", "hash", null,
                new LoanParams(new java.math.BigDecimal("1000000"), new java.math.BigDecimal("100000"), 12,
                        new java.math.BigDecimal("12"), dev.nosurprise.calculator.PaymentType.ANNUITY, List.of()),
                new LoanSummary(new java.math.BigDecimal("80000.00"), new java.math.BigDecimal("10000.00"),
                        new java.math.BigDecimal("960000.00"), 12), null, List.of(), Instant.now(), null);
        when(repository.findById("507f1f77bcf86cd799439011")).thenReturn(Optional.of(doc));

        CalculationReadModel result = service.getCalculation("507f1f77bcf86cd799439011");

        assertThat(result.calcId()).isEqualTo("507f1f77bcf86cd799439011");
        assertThat(result.params().annualRate()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("getCalculation для отсутствующего расчёта -> CALC_NOT_FOUND")
    void missing() {
        CalculationRepository repository = mock(CalculationRepository.class);
        CalcCache cache = mock(CalcCache.class);
        CalculatorService service = new CalculatorService(repository, cache);
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCalculation("missing"))
                .isInstanceOf(dev.nosurprise.calc.service.CalcNotFoundException.class);
    }
}