package dev.nosurprise.sms.telegram.messaging;

import dev.nosurprise.sms.telegram.config.RabbitConfig;
import dev.nosurprise.sms.telegram.service.BindingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Держит привязку Telegram в актуальном состоянии: смена номера переписывает телефон,
 * блокировка аккаунта отвязывает чат (после разблокировки нужна повторная привязка).
 */
@Component
public class UserSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserSyncConsumer.class);

    private final BindingService bindings;

    public UserSyncConsumer(BindingService bindings) {
        this.bindings = bindings;
    }

    @RabbitListener(queues = RabbitConfig.USER_SYNC_Q)
    public void handle(Map<String, Object> event, Message message,
                       @Header(name = "amqp_receivedRoutingKey", required = false) String routingKey) {
        String type = routingKey != null ? routingKey : String.valueOf(event.getOrDefault("eventType", ""));
        UUID userId = parseUuid(event.get("userId"));
        if (userId == null) {
            // Unparseable event: ретраи и DLQ ничего не исправят, поэтому фиксируем и пропускаем.
            log.warn("Ignoring user-sync event without a valid userId: type={} payload={}", type, event);
            return;
        }

        switch (type) {
            case "user.phone.changed" -> {
                Object newPhone = event.get("newPhone");
                if (newPhone == null || String.valueOf(newPhone).isBlank()) {
                    log.warn("user.phone.changed for user {} has no newPhone, binding left as is", userId);
                    return;
                }
                bindings.updatePhone(userId, String.valueOf(newPhone));
            }
            case "user.blocked" -> bindings.unlink(userId);
            default -> log.debug("Ignoring user-sync event type={}", type);
        }
    }

    private static UUID parseUuid(Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
