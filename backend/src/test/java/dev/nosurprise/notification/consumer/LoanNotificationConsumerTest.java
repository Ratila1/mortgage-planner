package dev.nosurprise.notification.consumer;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.common.outbox.ProcessedEvents;
import dev.nosurprise.loan.application.LoanApplicationEvent;
import dev.nosurprise.loan.application.LoanApplicationService;
import dev.nosurprise.loan.application.LoanNotificationView;
import dev.nosurprise.loan.application.LoanStatus;
import dev.nosurprise.notification.NotificationService;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Уведомления: маркер подписи при подаче меняет только новые заявки, legacy-путь не трогается. */
class LoanNotificationConsumerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID APP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DOC_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    private ProcessedEvents processed;
    private LoanApplicationService loans;
    private NotificationService notifications;
    private UserService users;
    private LoanNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        processed = mock(ProcessedEvents.class);
        loans = mock(LoanApplicationService.class);
        notifications = mock(NotificationService.class);
        users = mock(UserService.class);
        when(processed.markProcessed(any(), any())).thenReturn(true);
        when(users.findById(USER_ID)).thenReturn(Optional.empty()); // письма в тесте не нужны
        consumer = new LoanNotificationConsumer(processed, loans, notifications, users,
                mock(OutboxPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC), "https://example.com");
    }

    @Test
    @DisplayName("подписанная при подаче заявка после ACTIVE получает одно LOAN_ISSUED со ссылкой на договор")
    void signedActiveNotifiesOnce() {
        view(true, LoanStatus.ACTIVE);

        consumer.on(event("ACTIVE"));

        ArgumentCaptor<Map<String, Object>> payload = payload();
        verify(notifications).createNotification(eq(USER_ID), eq("LOAN_ISSUED"), payload.capture());
        assertThat(payload.getValue()).containsEntry("applicationId", APP_ID.toString())
                .containsEntry("contractUrl", "/api/loans/applications/" + APP_ID + "/documents/" + DOC_ID);
    }

    @Test
    @DisplayName("CONTRACT_READY для подписанной заявки не даёт уведомление «одобрена»")
    void signedContractReadyIsSuppressed() {
        view(true, LoanStatus.APPROVED);

        consumer.on(event(LoanApplicationEvent.CONTRACT_READY));

        verify(notifications, never()).createNotification(eq(USER_ID), any(), any());
    }

    @Test
    @DisplayName("legacy-заявка сохраняет LOAN_APPROVED и LOAN_SIGNED")
    void legacyNotificationsAreUnchanged() {
        view(false, LoanStatus.APPROVED);
        consumer.on(event(LoanApplicationEvent.CONTRACT_READY));
        verify(notifications).createNotification(eq(USER_ID), eq("LOAN_APPROVED"), any());

        view(false, LoanStatus.ACTIVE);
        consumer.on(event("ACTIVE"));
        verify(notifications).createNotification(eq(USER_ID), eq("LOAN_SIGNED"), any());
    }

    private void view(boolean signedAtSubmit, LoanStatus status) {
        when(loans.notificationView(APP_ID)).thenReturn(Optional.of(
                new LoanNotificationView(APP_ID, USER_ID, status, signedAtSubmit, null, DOC_ID)));
    }

    private static LoanApplicationEvent event(String status) {
        return new LoanApplicationEvent(UUID.randomUUID(), NOW, APP_ID, USER_ID, status);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> payload() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
