package com.bank.mortgage.calculator;

import java.math.BigDecimal;
import java.util.List;

public record CalculationResponse(
        BigDecimal monthlyPayment,
        BigDecimal overpayment,
        BigDecimal totalPayment,
        List<ScheduleEntry> schedule
) {
    public CalculationResponse {
        if (monthlyPayment == null || overpayment == null || totalPayment == null || schedule == null) {
            throw new IllegalArgumentException("All fields must be non-null");
        }
    }
}