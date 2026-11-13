package dev.nosurprise.calc.web.dto;

import dev.nosurprise.calculator.EarlyRepaymentMode;
import dev.nosurprise.calculator.EarlyRepaymentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Досрочное погашение в теле запроса (§5):
 * ONE_TIME → month; REGULAR → startMonth, everyMonths, untilMonth?.
 */
public record EarlyRepaymentRequest(
        @NotNull(message = "type is required")
        EarlyRepaymentType type,
        /** Только ONE_TIME. */
        Integer month,
        /** Только REGULAR. */
        Integer startMonth,
        /** Только REGULAR. */
        Integer everyMonths,
        /** Только REGULAR: последний месяц, null — до конца срока. */
        Integer untilMonth,
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be positive")
        BigDecimal amount,
        @NotNull(message = "mode is required")
        EarlyRepaymentMode mode
) {
}
