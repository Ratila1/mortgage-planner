package dev.nosurprise.offer.event;

import dev.nosurprise.common.messaging.RabbitEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OfferRateChangedPublisher {

    public static final String OFFER_RATE_CHANGED_RK = "offer.rate.changed";

    private final RabbitEventPublisher publisher;

    public OfferRateChangedPublisher(RabbitEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OfferRateChanged event) {
        publisher.publish(OFFER_RATE_CHANGED_RK, event);
    }
}