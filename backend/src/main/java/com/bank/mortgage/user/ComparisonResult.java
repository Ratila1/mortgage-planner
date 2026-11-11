package com.bank.mortgage.user;

import com.bank.mortgage.calculator.CalculationResponse;
import com.bank.mortgage.calculator.ScheduleEntry;

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