package dev.nosurprise.calculator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Входные данные расчёта (§5).
 * Все суммы — BigDecimal; валидация входа выполняется здесь.
 */
public record LoanParams(
        BigDecimal price,
        BigDecimal downPayment,
        int termMonths,
        BigDecimal annualRate,
        PaymentType type,
        List<EarlyRepayment> earlyRepayments
) {

    private static final BigDecimal MAX_RATE = new BigDecimal("100");

    public LoanParams {
        Objects.requireNonNull(price, "price is required");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        Objects.requireNonNull(downPayment, "downPayment is required");
        if (downPayment.signum() < 0) {
            throw new IllegalArgumentException("downPayment must be >= 0");
        }
        if (downPayment.compareTo(price) >= 0) {
            throw new IllegalArgumentException("downPayment must be less than price");
        }
        if (termMonths < 1 || termMonths > 360) {
            throw new IllegalArgumentException("termMonths must be in 1..360");
        }
        Objects.requireNonNull(annualRate, "annualRate is required");
        if (annualRate.signum() < 0 || annualRate.compareTo(MAX_RATE) > 0) {
            throw new IllegalArgumentException("annualRate must be in 0..100");
        }
        Objects.requireNonNull(type, "type is required");
        earlyRepayments = earlyRepayments == null ? List.of() : List.copyOf(earlyRepayments);
    }

    /** Сумма кредита: price − downPayment, всегда > 0. */
    public BigDecimal loanAmount() {
        return price.subtract(downPayment);
    }
}
