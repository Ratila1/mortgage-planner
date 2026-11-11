package dev.nosurprise.notification.event;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.config.RabbitMailConfig;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

@Component
public class ScenarioRecalculatedMailBridge {

    private final RabbitEventPublisher publisher;

    public ScenarioRecalculatedMailBridge(RabbitEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ScenarioRecalculatedMailRequested event) {
        publisher.publish(RabbitMailConfig.MAIL_REQUESTED_RK, new MailRequested(
                event.mailEventId(), event.occurredAt(), event.to(), event.subject(), event.body()));
    }
}