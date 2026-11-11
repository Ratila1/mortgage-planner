package dev.nosurprise.mortgage.offer;

import java.math.BigDecimal;
import java.time.Instant;

public record RateChangedEvent(
        Long offerId,
        String bankName,
        BigDecimal oldRate,
        BigDecimal newRate,
        Instant timestamp
) {}







