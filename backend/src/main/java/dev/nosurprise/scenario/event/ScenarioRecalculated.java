package dev.nosurprise.scenario.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ScenarioRecalculated(
        UUID eventId,
        Instant occurredAt,
        UUID scenarioId,
        String calcId,
        UUID userId,
        BigDecimal oldRate,
        BigDecimal newRate,
        BigDecimal oldMonthlyPayment,
        BigDecimal newMonthlyPayment,
        BigDecimal oldOverpayment,
        BigDecimal newOverpayment
) {
}