package dev.nosurprise.calc.web;

import dev.nosurprise.calc.web.dto.CalcResponse;
import dev.nosurprise.calc.web.dto.ScheduleRowResponse;
import dev.nosurprise.calc.web.dto.SavingsResponse;
import dev.nosurprise.calculator.CalculationResult;
import dev.nosurprise.common.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * Расчёт (calculator) → CalcResponse. Все деньги наружу — строки (§9).
 */public final class CalcResponseMapper {

    private CalcResponseMapper() {
    }

    public static CalcResponse toResponse(String calcId, BigDecimal loanAmount, CalculationResult result) {
        SavingsResponse savings = result.savings() == null ? null : new SavingsResponse(
                result.savings().overpaymentSaved().toPlainString(),
                result.savings().monthsSaved());        List<ScheduleRowResponse> schedule = result.schedule().stream()
                .map(row -> new ScheduleRowResponse(
                        row.month(),
                        row.payment().toPlainString(),
                        row.principal().toPlainString(),
                        row.interest().toPlainString(),
                        row.earlyRepayment().toPlainString(),
                        row.balance().toPlainString()))
                .toList();
        return new CalcResponse(
                calcId,
                Money.round(loanAmount).toPlainString(),
                result.summary().monthlyPayment().toPlainString(),
                result.summary().overpayment().toPlainString(),
                result.summary().totalPaid().toPlainString(),
                result.summary().termMonths(),
                savings,
                schedule);
    }
}
