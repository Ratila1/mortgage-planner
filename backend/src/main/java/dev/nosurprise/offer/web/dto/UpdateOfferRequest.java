package dev.nosurprise.offer.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateOfferRequest(
        @NotBlank String bankName,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal rate,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal minDownPaymentPercent,
        @NotNull @Min(1) @Max(360) Integer maxTermMonths,
        @NotNull Boolean active
) {
}