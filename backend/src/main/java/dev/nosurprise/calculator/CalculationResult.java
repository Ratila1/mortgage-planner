package dev.nosurprise.calculator;

import java.util.List;

/**
 * Результат расчёта. savings == null, если досрочных погашений не было.
 */
public record CalculationResult(
        LoanSummary summary,
        List<ScheduleRow> schedule,
        Savings savings
) {

    public CalculationResult {
        List.copyOf(schedule);
    }
}
