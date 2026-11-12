package dev.nosurprise.offer.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Событие offer.rate.changed (§8): JSON-record с eventId и occurredAt,
 * плюс offerId, старая и новая ставка.
 */
public record OfferRateChanged(UUID eventId, Instant occurredAt, UUID offerId,
                               BigDecimal oldRate, BigDecimal newRate) {
}