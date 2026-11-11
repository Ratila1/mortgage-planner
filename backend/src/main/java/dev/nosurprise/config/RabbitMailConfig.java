package dev.nosurprise.config;

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
 * RabbitMQ mail-пайплайн (§8): topic-exchange domain.events, очередь mail.send.q
 * с DLQ mail.send.q.dlq через DLX domain.events.dlx (routing key сохраняется).
 * Ретраи потребителя — spring.rabbitmq.listener.simple.retry в application.yml
 * (3 попытки, 1с/2с/4с); после исчерпания сообщение уходит в DLQ.
 */
@Configuration
public class RabbitMailConfig {

    public static final String EVENTS_EXCHANGE = "domain.events";
    public static final String DLX_EXCHANGE = "domain.events.dlx";
    public static final String MAIL_REQUESTED_RK = "mail.requested";
    public static final String MAIL_SEND_Q = "mail.send.q";
    public static final String MAIL_SEND_DLQ = "mail.send.q.dlq";

    @Bean
    Declarables rabbitMailDeclarables() {
        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX_EXCHANGE, true, false);

        Queue mailSendQ = QueueBuilder.durable(MAIL_SEND_Q)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(MAIL_REQUESTED_RK)
                .build();
        Queue mailSendDlq = QueueBuilder.durable(MAIL_SEND_DLQ).build();

        Binding mailSendBinding = BindingBuilder.bind(mailSendQ).to(events).with(MAIL_REQUESTED_RK);
        Binding mailDlqBinding = BindingBuilder.bind(mailSendDlq).to(dlx).with(MAIL_REQUESTED_RK);

        return new Declarables(events, dlx, mailSendQ, mailSendDlq, mailSendBinding, mailDlqBinding);
    }

    /** JSON-сообщения (Jackson 3, Boot 4). Бин подхватывается авто-конфигурацией RabbitTemplate/listener. */
    @Bean
    MessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
