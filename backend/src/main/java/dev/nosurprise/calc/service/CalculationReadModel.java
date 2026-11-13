package dev.nosurprise.calc.service;

import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.LoanSummary;
import dev.nosurprise.calculator.ScheduleRow;

import java.time.Instant;
import java.util.List;

public record CalculationReadModel(
        String calcId,
        LoanParams params,
        LoanSummary summary,
        List<ScheduleRow> schedule,
        Instant createdAt,
        String userId
) {
}