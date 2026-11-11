package dev.nosurprise.mortgage.calculator;

import java.math.BigDecimal;

public record EarlyRepayment(
        BigDecimal amount,
        int month,
        EarlyRepaymentMode mode
) {
    public EarlyRepayment {
        // Compact constructor validation removed - use validate() method
    }

    public void validate() {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Early repayment amount must be positive");
        }
        if (month <= 0) {
            throw new IllegalArgumentException("Early repayment month must be positive");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Early repayment mode must not be null");
        }
    }
}







