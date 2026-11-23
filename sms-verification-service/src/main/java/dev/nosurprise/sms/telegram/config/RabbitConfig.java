package dev.nosurprise.sms.telegram.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for Telegram verification. The domain exchanges are shared
 * with the main backend and declared idempotently.
 */
@Configuration
public class RabbitConfig {

    public static final String EVENTS_EXCHANGE = "domain.events";
    public static final String DLX_EXCHANGE = "domain.events.dlx";

    public static final String VERIFICATION_REQUESTED_RK = "verification.requested";
    public static final String VERIFICATION_REQUESTED_Q = "telegram.verification.q";

    public static final String USER_SYNC_Q = "telegram.user-sync.q";

    public static final String VERIFICATION_SENT_RK = "verification.sent";
    public static final String VERIFICATION_COMPLETED_RK = "verification.completed";
    public static final String TELEGRAM_LINKED_RK = "telegram.linked";
    public static final String TELEGRAM_UNLINKED_RK = "telegram.unlinked";

    @Bean
    Declarables telegramDeclarables() {
        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX_EXCHANGE, true, false);

        Queue verificationQueue = QueueBuilder.durable(VERIFICATION_REQUESTED_Q)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(VERIFICATION_REQUESTED_RK)
                .build();
        Queue verificationDlq = QueueBuilder.durable(VERIFICATION_REQUESTED_Q + ".dlq").build();

        Queue userSyncQueue = QueueBuilder.durable(USER_SYNC_Q)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey("user.phone.changed")
                .build();
        // Подписываемся только на события, которые меняют привязку: user.registered и user.unblocked
        // действий не требуют (после блокировки чат уже отвязан).
        Queue userSyncDlq = QueueBuilder.durable(USER_SYNC_Q + ".dlq").build();

        return new Declarables(
                events, dlx,
                verificationQueue, verificationDlq,
                userSyncQueue, userSyncDlq,
                BindingBuilder.bind(verificationQueue).to(events).with(VERIFICATION_REQUESTED_RK),
                BindingBuilder.bind(verificationDlq).to(dlx).with(VERIFICATION_REQUESTED_RK),
                BindingBuilder.bind(userSyncQueue).to(events).with("user.phone.changed"),
                BindingBuilder.bind(userSyncQueue).to(events).with("user.blocked"),
                BindingBuilder.bind(userSyncDlq).to(dlx).with("user.phone.changed")
        );
    }

    @Bean
    MessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
