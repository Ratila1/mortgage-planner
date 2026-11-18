package dev.nosurprise.loan.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LoanQuoteCalculatorTest {

    @Test
    @DisplayName("эталон §3.2: 1000 BYN, 1 % в день, 10 дней, модификатор 150 → проценты 100, к возврату 1250")
    void referenceExample() {
        LoanQuote quote = LoanQuoteCalculator.quote(new BigDecimal("1000.00"), 10, new BigDecimal("1.000"),
                new BigDecimal("150.00"));

        assertThat(quote.interest()).isEqualByComparingTo("100.00");
        assertThat(quote.modifier()).isEqualByComparingTo("150.00");
        assertThat(quote.total()).isEqualByComparingTo("1250.00");
    }

    @Test
    @DisplayName("проценты на модификатор не начисляются")
    void interestIgnoresModifier() {
        LoanQuote withModifier = LoanQuoteCalculator.quote(new BigDecimal("500"), 14, new BigDecimal("0.5"),
                new BigDecimal("150"));
        LoanQuote withoutModifier = LoanQuoteCalculator.quote(new BigDecimal("500"), 14, new BigDecimal("0.5"),
                BigDecimal.ZERO);

        assertThat(withModifier.interest()).isEqualByComparingTo(withoutModifier.interest());
        assertThat(withModifier.total().subtract(withoutModifier.total())).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("модификатор 0 и ставка 0: к возврату ровно сумма займа")
    void zeroRateAndModifier() {
        LoanQuote quote = LoanQuoteCalculator.quote(new BigDecimal("300.00"), 7, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(quote.interest()).isEqualByComparingTo("0.00");
        assertThat(quote.total()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("границы срока 7 и 30 дней")
    void termBoundaries() {
        assertThat(LoanQuoteCalculator.quote(new BigDecimal("1000"), 7, new BigDecimal("1"), BigDecimal.ZERO)
                .interest()).isEqualByComparingTo("70.00");
        assertThat(LoanQuoteCalculator.quote(new BigDecimal("1000"), 30, new BigDecimal("1"), BigDecimal.ZERO)
                .interest()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("округление процентов HALF_UP до копеек")
    void roundingHalfUp() {
        // 333.33 × 0.7 % × 13 = 30.333033 → 30.33; 100.05 × 0.5 % × 10 = 5.0025 → 5.00
        assertThat(LoanQuoteCalculator.quote(new BigDecimal("333.33"), 13, new BigDecimal("0.7"), BigDecimal.ZERO)
                .interest()).isEqualByComparingTo("30.33");
        assertThat(LoanQuoteCalculator.quote(new BigDecimal("100.10"), 10, new BigDecimal("0.5"), BigDecimal.ZERO)
                .interest()).isEqualByComparingTo("5.01");
    }
}
