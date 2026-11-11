package dev.nosurprise.calc.web.dto;

import dev.nosurprise.calculator.PaymentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record StressRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal price,
        @NotNull @DecimalMin(value = "0") BigDecimal downPayment,
        @NotNull @Min(1) @Max(360) Integer termMonths,
        @NotNull @DecimalMin(value = "0") @DecimalMax(value = "100") BigDecimal annualRate,
        @NotNull PaymentType type,
        @Valid List<EarlyRepaymentRequest> earlyRepayments,
        BigDecimal delta,
        List<BigDecimal> deltas
) {
}