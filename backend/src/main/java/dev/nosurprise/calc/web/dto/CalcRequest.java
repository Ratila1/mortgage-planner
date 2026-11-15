package dev.nosurprise.calc.web.dto;

import dev.nosurprise.calculator.PaymentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Тело POST /api/calc (§9). Деньги — строки в JSON, здесь они приходят как BigDecimal.
 * Кросс-поля досрочных погашений (ONE_TIME/REGULAR) проверяет CalcRequestMapper → InvalidCalculationException.
 */
public record CalcRequest(
        @NotNull(message = "price is required")
        @DecimalMin(value = "0.01", message = "price must be positive")
        @DecimalMax(value = "1000000000000", message = "price must be <= 1e12")
        BigDecimal price,
        @NotNull(message = "downPayment is required")
        @DecimalMin(value = "0", message = "downPayment must be >= 0")
        @DecimalMax(value = "1000000000000", message = "downPayment must be <= 1e12")
        BigDecimal downPayment,
        @NotNull(message = "termMonths is required")
        @Min(value = 1, message = "termMonths must be in 1..360")
        @Max(value = 360, message = "termMonths must be in 1..360")
        Integer termMonths,
        @NotNull(message = "annualRate is required")
        @DecimalMin(value = "0", message = "annualRate must be in 0..100")
        @DecimalMax(value = "100", message = "annualRate must be in 0..100")
        BigDecimal annualRate,
        @NotNull(message = "type is required")
        PaymentType type,
        @Valid
        @Size(max = 20, message = "earlyRepayments must contain at most 20 items")
        List<EarlyRepaymentRequest> earlyRepayments
) {
}
