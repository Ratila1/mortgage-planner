package dev.nosurprise.smsverificationservice.telegram.messaging;

import dev.nosurprise.smsverificationservice.telegram.config.RabbitConfig;
import dev.nosurprise.smsverificationservice.telegram.service.VerificationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class VerificationRequestedConsumer {

    private final VerificationService service;

    public VerificationRequestedConsumer(VerificationService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitConfig.VERIFICATION_REQUESTED_Q)
    public void handle(Map<String, Object> event) {
        UUID verificationId = UUID.fromString(String.valueOf(event.get("verificationId")));
        UUID userId = UUID.fromString(String.valueOf(event.get("userId")));
        String operationType = String.valueOf(event.get("operationType"));
        String operationRef = String.valueOf(event.get("operationRef"));
        UUID smsId = parseOptionalUuid(event.get("smsId"));
        int ttl = event.get("ttlMinutes") instanceof Number minutes ? minutes.intValue() : 5;
        service.handleRequested(verificationId, userId, operationType, operationRef, smsId, ttl);
    }

    private static UUID parseOptionalUuid(Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value);
        return raw.isBlank() || "null".equals(raw) ? null : UUID.fromString(raw);
    }
}
