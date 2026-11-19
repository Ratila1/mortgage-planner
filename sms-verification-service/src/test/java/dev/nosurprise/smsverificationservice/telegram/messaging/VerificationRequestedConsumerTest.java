package dev.nosurprise.smsverificationservice.telegram.messaging;

import dev.nosurprise.smsverificationservice.telegram.service.VerificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VerificationRequestedConsumerTest {

    private static final UUID VERIFICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SMS_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String OPERATION_REF = "33333333-3333-3333-3333-333333333333";

    private final VerificationService service = mock(VerificationService.class);
    private final VerificationRequestedConsumer consumer = new VerificationRequestedConsumer(service);

    @Test
    @DisplayName("smsId из события передаётся в сервис верификации")
    void forwardsSmsId() {
        consumer.handle(event(SMS_ID));

        verify(service).handleRequested(VERIFICATION_ID, USER_ID, "SIGNING", OPERATION_REF, SMS_ID, 5);
    }

    @Test
    @DisplayName("старое событие без smsId не ломает обработку")
    void oldEventWithoutSmsIdIsAccepted() {
        consumer.handle(event(null));

        verify(service).handleRequested(VERIFICATION_ID, USER_ID, "SIGNING", OPERATION_REF, null, 5);
    }

    private static Map<String, Object> event(UUID smsId) {
        Map<String, Object> event = new HashMap<>();
        event.put("verificationId", VERIFICATION_ID.toString());
        event.put("userId", USER_ID.toString());
        event.put("operationType", "SIGNING");
        event.put("operationRef", OPERATION_REF);
        event.put("ttlMinutes", 5);
        if (smsId != null) {
            event.put("smsId", smsId.toString());
        }
        return event;
    }
}
