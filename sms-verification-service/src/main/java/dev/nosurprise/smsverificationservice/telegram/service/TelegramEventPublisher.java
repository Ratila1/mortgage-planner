package dev.nosurprise.smsverificationservice.telegram.service;

import dev.nosurprise.smsverificationservice.telegram.config.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Publishes Telegram verification events into the shared domain exchange. */
@Component
public class TelegramEventPublisher {

    private final RabbitTemplate rabbit;
    private final Clock clock;

    public TelegramEventPublisher(RabbitTemplate rabbit, Clock clock) {
        this.rabbit = rabbit;
        this.clock = clock;
    }

    public void verificationSent(UUID verificationId, UUID userId, String operationType) {
        publish(RabbitConfig.VERIFICATION_SENT_RK, verificationId, userId,
                Map.of("operationType", operationType));
    }

    public void verificationCompleted(UUID verificationId, UUID userId, String operationType) {
        publish(RabbitConfig.VERIFICATION_COMPLETED_RK, verificationId, userId,
                Map.of("operationType", operationType));
    }

    public void telegramLinked(UUID userId, Long telegramId, String phone) {
        publish(RabbitConfig.TELEGRAM_LINKED_RK, null, userId,
                Map.of("telegramId", telegramId, "phone", phone));
    }

    public void telegramUnlinked(UUID userId) {
        publish(RabbitConfig.TELEGRAM_UNLINKED_RK, null, userId, Map.of());
    }

    private void publish(String routingKey, UUID verificationId, UUID userId, Map<String, Object> extra) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("occurredAt", clock.instant().toString());
        if (verificationId != null) event.put("verificationId", verificationId.toString());
        if (userId != null) event.put("userId", userId.toString());
        event.putAll(extra);
        rabbit.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, routingKey, event);
    }
}
