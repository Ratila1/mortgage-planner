package dev.nosurprise.scenario;

import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.LoanSummary;
import dev.nosurprise.calculator.ScheduleRow;
import dev.nosurprise.offer.web.dto.OfferResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScenarioExportData(
        UUID scenarioId,
        UUID userId,
        String name,
        LoanParams params,
        LoanSummary summary,
        List<ScheduleRow> schedule,
        String calcId,
        Instant createdAt,
        OfferResponse offer
) {
}