package dev.nosurprise.auth.event;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.config.RabbitMailConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Юнит-тест моста: VerificationEmailRequested → mail.requested (§8).
 * Routing key mail.requested, payload — JSON-record с eventId (UUID), occurredAt
 * и телом письма со ссылкой подтверждения.
 */
@ExtendWith(MockitoExtension.class)
class VerificationEmailMailBridgeTest {

    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    RabbitEventPublisher publisher;

    VerificationEmailMailBridge bridge;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        bridge = new VerificationEmailMailBridge(publisher, BASE_URL);
    }

    @Test
    @DisplayName("мост публикует mail.requested с eventId, occurredAt и ссылкой подтверждения")
    void publishesMailRequested() {
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        bridge.on(new VerificationEmailRequested(userId, "user@example.com", "token-1"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(RabbitMailConfig.MAIL_REQUESTED_RK), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(MailRequested.class);
        MailRequested mail = (MailRequested) payload.getValue();
        assertThat(mail.eventId()).isNotNull();
        assertThat(mail.occurredAt()).isNotNull();
        assertThat(mail.to()).isEqualTo("user@example.com");
        assertThat(mail.body()).contains(BASE_URL + "/api/auth/verify-email?token=token-1");
    }

    @Test
    @DisplayName("разные события получают разные eventId")
    void distinctEventIds() {
        bridge.on(new VerificationEmailRequested(UUID.randomUUID(), "a@example.com", "t1"));
        bridge.on(new VerificationEmailRequested(UUID.randomUUID(), "b@example.com", "t2"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.times(2))
                .publish(eq(RabbitMailConfig.MAIL_REQUESTED_RK), payload.capture());
        assertThat(payload.getAllValues()).hasSize(2);
        UUID first = ((MailRequested) payload.getAllValues().get(0)).eventId();
        UUID second = ((MailRequested) payload.getAllValues().get(1)).eventId();
        assertThat(first).isNotEqualTo(second);
    }
}

