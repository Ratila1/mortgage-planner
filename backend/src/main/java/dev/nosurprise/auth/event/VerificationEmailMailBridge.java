package dev.nosurprise.auth.event;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.config.RabbitMailConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Мост: in-app VerificationEmailRequested → mail.requested в Rabbit (§8).
 * Публикация строго ПОСЛЕ коммита транзакции — иначе consumer мог бы увидеть
 * ещё не зафиксированного пользователя.
 */
@Component
public class VerificationEmailMailBridge {

    private final RabbitEventPublisher publisher;
    private final String baseUrl;

    public VerificationEmailMailBridge(RabbitEventPublisher publisher,
                                       @Value("${app.base-url}") String baseUrl) {
        this.publisher = publisher;
        this.baseUrl = baseUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(VerificationEmailRequested event) {
        String confirmUrl = baseUrl + "/api/auth/verify-email?token=" + event.token();
        publisher.publish(RabbitMailConfig.MAIL_REQUESTED_RK, new MailRequested(
                UUID.randomUUID(),
                Instant.now(),
                event.email(),
                "Confirm your email",
                "Confirm your email: " + confirmUrl + " . The link is valid for 24 hours."));
    }
}
