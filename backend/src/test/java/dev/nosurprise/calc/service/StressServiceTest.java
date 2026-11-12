package dev.nosurprise.calc.service;

import dev.nosurprise.calc.web.dto.StressRequest;
import dev.nosurprise.calc.web.dto.StressResponse;
import dev.nosurprise.calculator.InvalidCalculationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StressServiceTest {

    private final StressService service = new StressService();

    @Test
    @DisplayName("stress для аннуитета возвращает список delta")
    void annuityStress() {
        StressRequest request = new StressRequest(
                new BigDecimal("1000000"), new BigDecimal("100000"), 12, new BigDecimal("12"),
                dev.nosurprise.calculator.PaymentType.ANNUITY, List.of(),
                new BigDecimal("2"), null);

        List<StressResponse> result = service.stress(request);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).delta()).isEqualByComparingTo("2");
        assertThat(result.get(0).monthlyPayment()).isNotBlank();
        assertThat(result.get(0).overpayment()).isNotBlank();
    }

    @Test
    @DisplayName("delta и deltas одновременно -> ошибка")
    void bothDeltaAndDeltas() {
        StressRequest request = new StressRequest(
                new BigDecimal("1000000"), new BigDecimal("100000"), 12, new BigDecimal("12"),
                dev.nosurprise.calculator.PaymentType.ANNUITY, List.of(),
                new BigDecimal("1"), List.of(new BigDecimal("2")));

        assertThatThrownBy(() -> service.stress(request))
                .isInstanceOf(InvalidCalculationException.class);
    }

    @Test
    @DisplayName("ни delta, ни deltas -> ошибка")
    void missingDelta() {
        StressRequest request = new StressRequest(
                new BigDecimal("1000000"), new BigDecimal("100000"), 12, new BigDecimal("12"),
                dev.nosurprise.calculator.PaymentType.ANNUITY, List.of(),
                null, null);

        assertThatThrownBy(() -> service.stress(request))
                .isInstanceOf(InvalidCalculationException.class);
    }

    @Test
    @DisplayName("итоговая ставка вне 0..100 -> ошибка")
    void invalidStressedRate() {
        StressRequest request = new StressRequest(
                new BigDecimal("1000000"), new BigDecimal("100000"), 12, new BigDecimal("99"),
                dev.nosurprise.calculator.PaymentType.ANNUITY, List.of(),
                new BigDecimal("5"), null);

        assertThatThrownBy(() -> service.stress(request))
                .isInstanceOf(InvalidCalculationException.class);
    }
}