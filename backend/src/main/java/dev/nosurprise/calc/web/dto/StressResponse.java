package dev.nosurprise.calc.web.dto;

import java.math.BigDecimal;

public record StressResponse(
        BigDecimal delta,
        String monthlyPayment,
        String overpayment
) {
}