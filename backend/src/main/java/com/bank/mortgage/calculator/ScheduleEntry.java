package com.bank.mortgage.calculator;

import java.math.BigDecimal;

public record ScheduleEntry(
        int month,
        BigDecimal payment,
        BigDecimal principal,
        BigDecimal interest,
        BigDecimal remaining
) {
    public ScheduleEntry {
        if (month < 0) {
            throw new IllegalArgumentException("Month must be non-negative");
        }
    }
}