package dev.nosurprise.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitNotificationConfig {

    public static final String OFFER_RATE_CHANGED_Q = "notification.offer-rate-changed.q";
    public static final String OFFER_RATE_CHANGED_DLQ = "notification.offer-rate-changed.q.dlq";
    public static final String OFFER_RATE_CHANGED_RK = "offer.rate.changed";

    @Bean
    Declarables rabbitNotificationDeclarables() {
        TopicExchange events = new TopicExchange(RabbitMailConfig.EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(RabbitMailConfig.DLX_EXCHANGE, true, false);

        Queue queue = QueueBuilder.durable(OFFER_RATE_CHANGED_Q)
                .deadLetterExchange(RabbitMailConfig.DLX_EXCHANGE)
                .deadLetterRoutingKey(OFFER_RATE_CHANGED_RK)
                .build();
        Queue dlq = QueueBuilder.durable(OFFER_RATE_CHANGED_DLQ).build();

        Binding binding = BindingBuilder.bind(queue).to(events).with(OFFER_RATE_CHANGED_RK);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(OFFER_RATE_CHANGED_RK);

        // Очередь не объявляется: у неё нет потребителя (см. OfferRateChangedConsumer).
        return new Declarables();
    }
}