package dev.nosurprise.calculator;

import java.math.BigDecimal;

/**
 * Строка графика (§5): {@code month, payment, principal, interest, earlyRepayment, balance}.
 * Все суммы — BigDecimal scale 2; в API отдаются строками.
 * balance — остаток долга на конец месяца (после планового платежа и досрочных погашений).
 */
public record ScheduleRow(
        int month,
        BigDecimal payment,
        BigDecimal principal,
        BigDecimal interest,
        BigDecimal earlyRepayment,
        BigDecimal balance
) {
}
