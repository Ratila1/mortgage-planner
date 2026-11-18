package dev.nosurprise.loan.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebtLoadTest {

    @Test
    @DisplayName("ПДН = total / зарплата × 100 %, округление до 0.01 HALF_UP")
    void percent() {
        assertThat(DebtLoad.percent(new BigDecimal("1250.00"), new BigDecimal("2500.00"))).isEqualByComparingTo("50.00");
        assertThat(DebtLoad.percent(new BigDecimal("100.00"), new BigDecimal("300.00"))).isEqualByComparingTo("33.33");
        assertThat(DebtLoad.percent(new BigDecimal("200.00"), new BigDecimal("300.00"))).isEqualByComparingTo("66.67");
    }

    @Test
    @DisplayName("предупреждение только строго выше порога")
    void warningThreshold() {
        assertThat(DebtLoad.exceeds(new BigDecimal("50.00"), new BigDecimal("50"))).isFalse();
        assertThat(DebtLoad.exceeds(new BigDecimal("50.01"), new BigDecimal("50"))).isTrue();
    }

    @Test
    @DisplayName("зарплата 0 — ошибка кода (валидация не пропускает такие данные)")
    void zeroIncomeRejected() {
        assertThatThrownBy(() -> DebtLoad.percent(BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
