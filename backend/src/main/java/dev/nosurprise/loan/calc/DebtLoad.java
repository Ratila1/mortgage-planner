package dev.nosurprise.loan.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * ПДН — показатель долговой нагрузки (MICROLOANS.md §3.7). Не хранится, считается на лету из снимка заявки:
 * ПДН = total / monthly_income × 100 %, округление до 0.01 % HALF_UP.
 */
public final class DebtLoad {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DebtLoad() {
    }

    public static BigDecimal percent(BigDecimal total, BigDecimal monthlyIncome) {
        if (monthlyIncome == null || monthlyIncome.signum() <= 0) {
            // Зарплата > 0 гарантирована валидацией и CHECK в БД; сюда попадаем только при ошибке в коде.
            throw new IllegalArgumentException("monthly income must be positive");
        }
        return total.multiply(HUNDRED)
                .divide(monthlyIncome, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static boolean exceeds(BigDecimal percent, BigDecimal warningPercent) {
        return percent.compareTo(warningPercent) > 0;
    }
}
