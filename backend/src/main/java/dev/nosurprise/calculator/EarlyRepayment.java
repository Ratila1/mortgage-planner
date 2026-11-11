package dev.nosurprise.calculator;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Досрочное погашение (§5).
 * Разовое: {@code {type: ONE_TIME, month, amount}}.
 * Регулярное: {@code {type: REGULAR, startMonth, everyMonths, amount, untilMonth?}}.
 */
public record EarlyRepayment(
        EarlyRepaymentType type,
        /** Только ONE_TIME: месяц погашения. */
        Integer month,
        /** Только REGULAR: месяц первого погашения. */
        Integer startMonth,
        /** Только REGULAR: интервал в месяцах. */
        Integer everyMonths,
        /** Только REGULAR: последний месяц погашения, null — до конца срока. */
        Integer untilMonth,
        BigDecimal amount,
        EarlyRepaymentMode mode
) {

    public EarlyRepayment {
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(mode, "mode is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("earlyRepayment amount must be positive");
        }
        if (type == EarlyRepaymentType.ONE_TIME) {
            Objects.requireNonNull(month, "month is required for ONE_TIME");
            if (month < 1) {
                throw new IllegalArgumentException("earlyRepayment month must be >= 1");
            }
        } else {
            Objects.requireNonNull(startMonth, "startMonth is required for REGULAR");
            if (startMonth < 1) {
                throw new IllegalArgumentException("earlyRepayment startMonth must be >= 1");
            }
            Objects.requireNonNull(everyMonths, "everyMonths is required for REGULAR");
            if (everyMonths < 1) {
                throw new IllegalArgumentException("earlyRepayment everyMonths must be >= 1");
            }
            if (untilMonth != null && untilMonth < startMonth) {
                throw new IllegalArgumentException("earlyRepayment untilMonth must be >= startMonth");
            }
        }
    }

    public static EarlyRepayment oneTime(int month, BigDecimal amount, EarlyRepaymentMode mode) {
        return new EarlyRepayment(EarlyRepaymentType.ONE_TIME, month, null, null, null, amount, mode);
    }

    public static EarlyRepayment regular(int startMonth, int everyMonths, BigDecimal amount,
                                         Integer untilMonth, EarlyRepaymentMode mode) {
        return new EarlyRepayment(EarlyRepaymentType.REGULAR, null, startMonth, everyMonths, untilMonth, amount, mode);
    }
}
