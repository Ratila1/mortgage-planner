package dev.nosurprise.scenario;

import java.math.BigDecimal;
import java.util.UUID;

public record ScenarioRecalculationResult(
        UUID scenarioId,
        UUID userId,
        String calcId,
        BigDecimal oldRate,
        BigDecimal newRate,
        BigDecimal oldMonthlyPayment,
        BigDecimal newMonthlyPayment,
        BigDecimal oldOverpayment,
        BigDecimal newOverpayment
) {
}