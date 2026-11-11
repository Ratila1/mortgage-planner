package dev.nosurprise.calc.service;

import dev.nosurprise.calculator.EarlyRepayment;
import dev.nosurprise.calculator.EarlyRepaymentMode;
import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.PaymentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Канонизация paramsHash (§6.3): одинаковые параметры в разной записи → один хэш,
 * любые изменения параметров → другой хэш.
 */
class ParamsHasherTest {

    private LoanParams params(String price, String downPayment, int termMonths, String annualRate,
                              PaymentType type, List<EarlyRepayment> early) {
        return new LoanParams(new BigDecimal(price), new BigDecimal(downPayment), termMonths,
                new BigDecimal(annualRate), type, early);
    }

    private final LoanParams base = params("1000000", "0", 12, "12", PaymentType.ANNUITY, List.of());

    @Test
    @DisplayName("числа с ведущими/хвостовыми нулями канонизируются: 1.50 ≡ 1.5 ≡ 0001.50")
    void scaleCanonicalization() {
        LoanParams a = params("1000000.00", "0.00", 12, "12.0", PaymentType.ANNUITY, List.of());
        LoanParams b = params("1000000", "0", 12, "12", PaymentType.ANNUITY, List.of());

        assertThat(ParamsHasher.hash(a)).isEqualTo(ParamsHasher.hash(b));
        assertThat(ParamsHasher.hash(base)).isEqualTo(ParamsHasher.hash(b));
    }

    @Test
    @DisplayName("порядок досрочных погашений в списке не влияет на хэш")
    void earlyRepaymentOrderAgnostic() {
        EarlyRepayment oneTime = EarlyRepayment.oneTime(3, new BigDecimal("100000"), EarlyRepaymentMode.REDUCE_TERM);
        EarlyRepayment regular = EarlyRepayment.regular(6, 6, new BigDecimal("50000"), null, EarlyRepaymentMode.REDUCE_PAYMENT);

        LoanParams a = params("1000000", "0", 12, "12", PaymentType.ANNUITY, List.of(oneTime, regular));
        LoanParams b = params("1000000", "0", 12, "12", PaymentType.ANNUITY, List.of(regular, oneTime));

        assertThat(ParamsHasher.hash(a)).isEqualTo(ParamsHasher.hash(b));
    }

    @Test
    @DisplayName("любое изменение параметров меняет хэш")
    void differentParamsDifferentHash() {
        assertThat(ParamsHasher.hash(params("1000001", "0", 12, "12", PaymentType.ANNUITY, List.of())))
                .isNotEqualTo(ParamsHasher.hash(base));
        assertThat(ParamsHasher.hash(params("1000000", "1", 12, "12", PaymentType.ANNUITY, List.of())))
                .isNotEqualTo(ParamsHasher.hash(base));
        assertThat(ParamsHasher.hash(params("1000000", "0", 13, "12", PaymentType.ANNUITY, List.of())))
                .isNotEqualTo(ParamsHasher.hash(base));
        assertThat(ParamsHasher.hash(params("1000000", "0", 12, "12.01", PaymentType.ANNUITY, List.of())))
                .isNotEqualTo(ParamsHasher.hash(base));
        assertThat(ParamsHasher.hash(params("1000000", "0", 12, "12", PaymentType.DIFFERENTIATED, List.of())))
                .isNotEqualTo(ParamsHasher.hash(base));

        LoanParams withEarly = params("1000000", "0", 12, "12", PaymentType.ANNUITY,
                List.of(EarlyRepayment.oneTime(3, new BigDecimal("100000"), EarlyRepaymentMode.REDUCE_TERM)));
        assertThat(ParamsHasher.hash(withEarly)).isNotEqualTo(ParamsHasher.hash(base));
    }

    @Test
    @DisplayName("хэш — sha256: 64 hex-символа")
    void hashShape() {
        String hash = ParamsHasher.hash(base);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }
}
