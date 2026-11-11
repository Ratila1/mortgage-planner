package com.bank.mortgage.offer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public record OfferRequest(
        @NotBlank @jakarta.validation.constraints.Size(max = 255) String bankName,
        @NotNull @DecimalMin(value = "0.0001", message = "Rate must be positive") BigDecimal rate,
        @NotNull @DecimalMin(value = "0.01", message = "Min down payment must be at least 0.01%") BigDecimal minDownPaymentPercent,
        @NotNull @Positive int maxTermMonths
) {}