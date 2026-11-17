package dev.nosurprise.loan.calc;

import java.math.BigDecimal;

/** Результат расчёта микрозайма: проценты, модификатор типа и сумма к возврату (BYN). */
public record LoanQuote(BigDecimal amount, int termDays, BigDecimal dailyRate, BigDecimal interest,
                        BigDecimal modifier, BigDecimal total) {
}
