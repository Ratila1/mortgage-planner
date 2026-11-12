package dev.nosurprise.calculator;

import java.math.BigDecimal;

/**
 * Итоги расчёта (§6.2 summary).
 * monthlyPayment — первый плановый платёж графика;
 * overpayment — переплата = сумма начисленных процентов;
 * totalPaid — все выплаченные деньги (платежи + досрочные погашения);
 * termMonths — фактический срок (число строк графика).
 */
public record LoanSummary(
        BigDecimal monthlyPayment,
        BigDecimal overpayment,
        BigDecimal totalPaid,
        int termMonths
) {
}
