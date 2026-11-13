package dev.nosurprise.offer.event;

import dev.nosurprise.common.messaging.RabbitEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OfferRateChangedPublisherTest {

    @Test
    @DisplayName("listener публикует offer.rate.changed в Rabbit")
    void publishesToRabbit() {
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        OfferRateChangedPublisher listener = new OfferRateChangedPublisher(publisher);
        OfferRateChanged event = new OfferRateChanged(UUID.randomUUID(), Instant.now(), UUID.randomUUID(),
                new BigDecimal("9.50"), new BigDecimal("10.00"));

        listener.on(event);

        verify(publisher).publish("offer.rate.changed", event);
    }
}