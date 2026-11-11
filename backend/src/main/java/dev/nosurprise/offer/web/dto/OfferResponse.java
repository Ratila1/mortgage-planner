package dev.nosurprise.offer.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OfferResponse(
        UUID id,
        String bankName,
        String rate,
        String minDownPaymentPercent,
        int maxTermMonths,
        boolean active,
        Instant updatedAt
) {
    public static OfferResponse from(dev.nosurprise.offer.BankOffer offer) {
        return new OfferResponse(
                offer.getId(),
                offer.getBankName(),
                offer.getRate().toPlainString(),
                offer.getMinDownPaymentPercent().toPlainString(),
                offer.getMaxTermMonths(),
                offer.isActive(),
                offer.getUpdatedAt());
    }
}