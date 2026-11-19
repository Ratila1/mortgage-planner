package dev.nosurprise.telegram;

import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.telegram.event.PhoneChangedEvent;
import dev.nosurprise.telegram.event.UserRegisteredEvent;
import dev.nosurprise.telegram.event.UserUnblockedEvent;
import dev.nosurprise.user.UserBlocked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Bridges user lifecycle events into domain.events for Telegram binding sync. */
@Component
public class TelegramUserEventBridge {

    private static final Logger log = LoggerFactory.getLogger(TelegramUserEventBridge.class);

    private final RabbitEventPublisher publisher;
    private final Clock clock;

    public TelegramUserEventBridge(RabbitEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistered(UserRegisteredEvent event) {
        publish(TelegramRoutingKeys.USER_REGISTERED, event.userId(),
                Map.of("email", event.email() == null ? "" : event.email()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPhoneChanged(PhoneChangedEvent event) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("oldPhone", event.oldPhone() == null ? "" : event.oldPhone());
        payload.put("newPhone", event.newPhone() == null ? "" : event.newPhone());
        publish(TelegramRoutingKeys.USER_PHONE_CHANGED, event.userId(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlocked(UserBlocked event) {
        publish(TelegramRoutingKeys.USER_BLOCKED, event.userId(), Map.of());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUnblocked(UserUnblockedEvent event) {
        publish(TelegramRoutingKeys.USER_UNBLOCKED, event.userId(), Map.of());
    }

    private void publish(String routingKey, UUID userId, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.HashMap<>(extra);
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("occurredAt", clock.instant().toString());
        payload.put("userId", userId.toString());
        try {
            publisher.publish(routingKey, payload);
        } catch (RuntimeException ex) {
            // Binding sync is best-effort: a Rabbit failure must not roll back a committed user change.
            log.warn("Failed to publish {} for user {}: {}", routingKey, userId, ex.getMessage());
        }
    }
}
