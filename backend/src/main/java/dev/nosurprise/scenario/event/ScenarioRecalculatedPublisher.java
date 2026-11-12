package dev.nosurprise.scenario.event;

import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.config.RabbitScenarioConfig;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ScenarioRecalculatedPublisher {

    private final RabbitEventPublisher publisher;

    public ScenarioRecalculatedPublisher(RabbitEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ScenarioRecalculated event) {
        publisher.publish(RabbitScenarioConfig.SCENARIO_RECALCULATED_RK, event);
    }
}