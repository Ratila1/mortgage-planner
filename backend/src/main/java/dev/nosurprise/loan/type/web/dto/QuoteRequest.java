package dev.nosurprise.loan.type.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record QuoteRequest(
        @NotNull UUID loanTypeId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull Integer termDays
) {
}
