package dev.nosurprise.notificationservice.messaging;

import dev.nosurprise.notificationservice.config.RabbitConfig;
import dev.nosurprise.notificationservice.service.BindingService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class UserSyncConsumer {
    private final BindingService bindingService;

    public UserSyncConsumer(BindingService bindingService) { this.bindingService = bindingService; }

    @RabbitListener(queues = RabbitConfig.USER_SYNC_Q)
    public void handle(Map<String, Object> event) {
        String type = String.valueOf(event.getOrDefault("eventType", ""));
        if ("user.phone.changed".equals(type) || event.containsKey("newPhone")) {
            UUID userId = UUID.fromString(String.valueOf(event.get("userId")));
            String newPhone = String.valueOf(event.get("newPhone"));
            bindingService.updatePhone(userId, newPhone);
        }
        if ("user.blocked".equals(type)) {
            UUID userId = UUID.fromString(String.valueOf(event.get("userId")));
            bindingService.unlink(userId);
        }
    }
}