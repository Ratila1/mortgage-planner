package dev.nosurprise.notification.consumer;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.common.outbox.ProcessedEvents;
import dev.nosurprise.config.RabbitLoanConfig;
import dev.nosurprise.config.RabbitMailConfig;
import dev.nosurprise.loan.application.LoanApplicationEvent;
import dev.nosurprise.loan.application.LoanApplicationService;
import dev.nosurprise.loan.application.LoanNotificationView;
import dev.nosurprise.notification.NotificationService;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Уведомления по займам (MICROLOANS.md §3.9): запись в notifications и письмо.
 * В письме нет сумм, ФИО и причины отказа — только ссылка в личный кабинет. Причину клиент видит в ЛК.
 * Запись уведомления, отметка processed_events и событие mail.requested (через outbox) — одна транзакция:
 * повторная доставка не создаст второе уведомление и второе письмо.
 */
@Component
public class LoanNotificationConsumer {

    static final String CONSUMER = "notification.loan";
    private static final Logger log = LoggerFactory.getLogger(LoanNotificationConsumer.class);

    private final ProcessedEvents processed;
    private final LoanApplicationService loans;
    private final NotificationService notifications;
    private final UserService users;
    private final OutboxPublisher outbox;
    private final Clock clock;
    private final String baseUrl;

    public LoanNotificationConsumer(ProcessedEvents processed, LoanApplicationService loans,
                                    NotificationService notifications, UserService users, OutboxPublisher outbox,
                                    Clock clock, @Value("${app.base-url}") String baseUrl) {
        this.processed = processed;
        this.loans = loans;
        this.notifications = notifications;
        this.users = users;
        this.outbox = outbox;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    @RabbitListener(queues = RabbitLoanConfig.NOTIFICATION_Q)
    @Transactional
    public void on(LoanApplicationEvent event) {
        if (!processed.markProcessed(CONSUMER, event.eventId())) {
            log.info("loan event {} already processed, skipping", event.eventId());
            return;
        }
        LoanNotificationView view = loans.notificationView(event.applicationId()).orElse(null);
        if (view == null) {
            log.warn("loan application {} not found for event {}", event.applicationId(), event.eventId());
            return;
        }
        // Заявки, подписанные при подаче, получают одно уведомление после ACTIVE — «одобрена» не нужно.
        if (LoanApplicationEvent.CONTRACT_READY.equals(event.status()) && view.signedAtSubmit()) {
            return;
        }
        String cardUrl = "/loans/" + view.applicationId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", view.applicationId().toString());
        payload.put("cardUrl", cardUrl);

        String subject;
        String notificationType;
        switch (event.status()) {
            case LoanApplicationEvent.CONTRACT_READY -> {
                // Письмо уходит, когда договор уже сгенерирован: по ссылке клиент сразу может его скачать и подписать.
                subject = "Ваша заявка на микрозайм одобрена";
                notificationType = "LOAN_APPROVED";
                if (view.contractDocumentId() != null) {
                    payload.put("contractUrl", "/api/loans/applications/" + view.applicationId()
                            + "/documents/" + view.contractDocumentId());
                }
                payload.put("signUrl", cardUrl);
            }
            case "REJECTED" -> {
                subject = "По вашей заявке принято решение";
                notificationType = "LOAN_REJECTED";
                payload.put("reason", view.decisionComment());
            }
            case "ACTIVE" -> {
                if (view.signedAtSubmit()) {
                    subject = "Заём выдан";
                    notificationType = "LOAN_ISSUED";
                    if (view.contractDocumentId() != null) {
                        payload.put("contractUrl", "/api/loans/applications/" + view.applicationId()
                                + "/documents/" + view.contractDocumentId());
                    }
                } else {
                    subject = "Договор подписан";
                    notificationType = "LOAN_SIGNED";
                }
            }
            case "EXPIRED" -> {
                subject = "Срок подписания договора истёк";
                notificationType = "LOAN_EXPIRED";
            }
            case "REPAID" -> {
                subject = "Заём закрыт";
                notificationType = "LOAN_REPAID";
            }
            case "OVERDUE" -> {
                // День 1 просрочки (CREDIT_POLICY.md §5.1).
                subject = "Просрочка по займу";
                notificationType = "LOAN_OVERDUE";
            }
            case "OVERDUE_REMINDER" -> {
                // Напоминания на 3 и 7 день просрочки.
                subject = "Напоминание: заём просрочен";
                notificationType = "LOAN_OVERDUE";
            }
            default -> {
                log.warn("unknown loan event status {} in event {}", event.status(), event.eventId());
                return;
            }
        }
        if (notificationType != null) {
            notifications.createNotification(view.userId(), notificationType, payload);
        }
        sendMail(view.userId(), event.eventId(), subject, cardUrl);
    }

    private void sendMail(UUID userId, UUID eventId, String subject, String cardUrl) {
        UserEntity user = users.findById(userId).orElse(null);
        if (user == null || !user.isEmailVerified() || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        String body = "Здравствуйте!\n\n" + subject + ".\n"
                + "Подробности — в личном кабинете: " + baseUrl + cardUrl + "\n\n"
                + "С уважением,\nкоманда сервиса";
        // Детерминированный eventId письма: при повторной обработке MailSendConsumer не отправит второе письмо.
        UUID mailEventId = UUID.nameUUIDFromBytes(("loan-mail:" + eventId).getBytes(StandardCharsets.UTF_8));
        outbox.publish(RabbitMailConfig.MAIL_REQUESTED_RK, "mail", userId,
                new MailRequested(mailEventId, clock.instant(), user.getEmail(), subject, body));
    }
}
