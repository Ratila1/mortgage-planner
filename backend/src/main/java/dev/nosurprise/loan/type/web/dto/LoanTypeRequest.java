package dev.nosurprise.loan.type.web.dto;

import dev.nosurprise.loan.type.LoanTypeData;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Тип займа от админа. version обязателен при PUT (оптимистичная блокировка). */
public record LoanTypeRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 1000) String description,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal minAmount,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal maxAmount,
        @NotNull @Min(7) @Max(30) Integer minTermDays,
        @NotNull @Min(7) @Max(30) Integer maxTermDays,
        @NotNull @DecimalMin("0") @Digits(integer = 3, fraction = 3) BigDecimal dailyRate,
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal modifier,
        @NotNull Boolean active,
        // балл цели для скоринга 0–1; не передан — при создании 0.50, при обновлении не меняется
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 2) BigDecimal purposeScore,
        Long version
) {

    public LoanTypeData toData() {
        return new LoanTypeData(name.trim(), description.trim(), minAmount, maxAmount, minTermDays, maxTermDays,
                dailyRate, modifier, active, purposeScore);
    }
}
