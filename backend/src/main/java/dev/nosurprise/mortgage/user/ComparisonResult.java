package dev.nosurprise.mortgage.user;

import dev.nosurprise.mortgage.calculator.CalculationResponse;
import dev.nosurprise.mortgage.calculator.ScheduleEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ComparisonResult(
        List<ScenarioSummary> scenarios,
        Map<String, BigDecimal> monthlyPaymentComparison,
        Map<String, BigDecimal> overpaymentComparison,
        Map<String, BigDecimal> totalPaymentComparison,
        Map<String, Integer> termComparison
) {
    public record ScenarioSummary(
            String id,
            String name,
            BigDecimal propertyPrice,
            BigDecimal downPayment,
            int termMonths,
            BigDecimal rate,
            String paymentType,
            BigDecimal monthlyPayment,
            BigDecimal overpayment,
            BigDecimal totalPayment
    ) {}
}







