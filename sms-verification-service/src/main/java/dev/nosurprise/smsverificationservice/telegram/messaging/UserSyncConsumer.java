package dev.nosurprise.smsverificationservice.telegram.messaging;

import dev.nosurprise.smsverificationservice.telegram.config.RabbitConfig;
import dev.nosurprise.smsverificationservice.telegram.service.BindingService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class UserSyncConsumer {

    private final BindingService bindings;

    public UserSyncConsumer(BindingService bindings) {
        this.bindings = bindings;
    }

    @RabbitListener(queues = RabbitConfig.USER_SYNC_Q)
    public void handle(Map<String, Object> event, Message message,
                       @Header(name = "amqp_receivedRoutingKey", required = false) String routingKey) {
        String type = routingKey != null ? routingKey : String.valueOf(event.getOrDefault("eventType", ""));
        UUID userId = UUID.fromString(String.valueOf(event.get("userId")));

        if ("user.phone.changed".equals(type) || event.containsKey("newPhone")) {
            bindings.updatePhone(userId, String.valueOf(event.get("newPhone")));
        } else if ("user.blocked".equals(type) || "user.unblocked".equals(type)) {
            if ("user.blocked".equals(type)) bindings.unlink(userId);
        }
    }
}
