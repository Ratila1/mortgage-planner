package dev.nosurprise.loan.type;

import java.math.BigDecimal;

/**
 * Данные типа займа от админа. Правила, которые не выразить аннотациями DTO, проверяет {@link #validate()}:
 * срок 7–30 дней (§3.1), min ≤ max. Те же ограничения продублированы CHECK-ами в V13.
 */
public record LoanTypeData(String name, String description, BigDecimal minAmount, BigDecimal maxAmount,
                           int minTermDays, int maxTermDays, BigDecimal dailyRate, BigDecimal modifier,
                           boolean active) {

    public static final int MIN_TERM_DAYS = 7;
    public static final int MAX_TERM_DAYS = 30;

    public void validate() {
        if (minAmount.signum() <= 0 || minAmount.compareTo(maxAmount) > 0) {
            throw new InvalidLoanTypeException("amount range must satisfy 0 < minAmount <= maxAmount");
        }
        if (minTermDays < MIN_TERM_DAYS || minTermDays > maxTermDays || maxTermDays > MAX_TERM_DAYS) {
            throw new InvalidLoanTypeException("term range must satisfy 7 <= minTermDays <= maxTermDays <= 30");
        }
        if (dailyRate.signum() < 0) {
            throw new InvalidLoanTypeException("dailyRate must be >= 0");
        }
        if (modifier.signum() < 0) {
            throw new InvalidLoanTypeException("modifier must be >= 0");
        }
    }
}
