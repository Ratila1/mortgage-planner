package dev.nosurprise.calc.web.dto;

import dev.nosurprise.calculator.EarlyRepaymentMode;
import dev.nosurprise.calculator.EarlyRepaymentType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Досрочное погашение в теле запроса (§5):
 * ONE_TIME → month; REGULAR → startMonth, everyMonths, untilMonth?.
 * Границы месяцев 1..360 заданы на уровне DTO: наращение графика
 * startMonth + everyMonths не должно зависеть от переполнения int в ядре.
 * Чужие поля типа (например, startMonth у ONE_TIME) молча игнорируются —
 * проверку «какие поля обязательны для какого типа» выполняет CalcRequestMapper.
 */
public record EarlyRepaymentRequest(
        @NotNull(message = "type is required")
        EarlyRepaymentType type,
        /** Только ONE_TIME. */
        @Min(value = 1, message = "month must be in 1..360")
        @Max(value = 360, message = "month must be in 1..360")
        Integer month,
        /** Только REGULAR. */
        @Min(value = 1, message = "startMonth must be in 1..360")
        @Max(value = 360, message = "startMonth must be in 1..360")
        Integer startMonth,
        /** Только REGULAR. */
        @Min(value = 1, message = "everyMonths must be >= 1")
        @Max(value = 360, message = "everyMonths must be <= 360")
        Integer everyMonths,
        /** Только REGULAR: последний месяц, null — до конца срока. */
        @Min(value = 1, message = "untilMonth must be in 1..360")
        @Max(value = 360, message = "untilMonth must be in 1..360")
        Integer untilMonth,
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be positive")
        @DecimalMax(value = "1000000000000", message = "amount must be <= 1e12")
        BigDecimal amount,
        @NotNull(message = "mode is required")
        EarlyRepaymentMode mode
) {
}
