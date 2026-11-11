package dev.nosurprise.mortgage.calculator;

import java.math.BigDecimal;
import java.util.List;

public record CalculationRequest(
        BigDecimal propertyPrice,
        BigDecimal downPayment,
        int termMonths,
        BigDecimal rate,
        PaymentType type,
        List<EarlyRepayment> earlyRepayments
) {
    public BigDecimal loanAmount() {
        return propertyPrice.subtract(downPayment);
    }

    public void validate() {
        if (propertyPrice == null || propertyPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Property price must be positive");
        }
        if (downPayment == null || downPayment.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Down payment must be non-negative");
        }
        if (downPayment.compareTo(propertyPrice) >= 0) {
            throw new IllegalArgumentException("Down payment must be less than property price");
        }
        if (termMonths <= 0) {
            throw new IllegalArgumentException("Term must be positive");
        }
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        if (type == null) {
            throw new IllegalArgumentException("Payment type must not be null");
        }
        if (earlyRepayments != null) {
            for (EarlyRepayment er : earlyRepayments) {
                if (er.month() > termMonths) {
                    throw new IllegalArgumentException("Early repayment month cannot exceed term");
                }
            }
        }
    }
}







