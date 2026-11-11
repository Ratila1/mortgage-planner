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
public class RabbitScenarioConfig {

    public static final String SCENARIO_RECALC_Q = "scenario.recalc.q";
    public static final String SCENARIO_RECALC_DLQ = "scenario.recalc.q.dlq";
    public static final String OFFER_RATE_CHANGED_RK = "offer.rate.changed";

    public static final String NOTIFICATION_Q = "notification.q";
    public static final String NOTIFICATION_DLQ = "notification.q.dlq";
    public static final String SCENARIO_RECALCULATED_RK = "scenario.recalculated";

    @Bean
    Declarables rabbitScenarioDeclarables() {
        TopicExchange events = new TopicExchange(RabbitMailConfig.EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(RabbitMailConfig.DLX_EXCHANGE, true, false);

        Queue recalcQ = QueueBuilder.durable(SCENARIO_RECALC_Q)
                .deadLetterExchange(RabbitMailConfig.DLX_EXCHANGE)
                .deadLetterRoutingKey(OFFER_RATE_CHANGED_RK)
                .build();
        Queue recalcDlq = QueueBuilder.durable(SCENARIO_RECALC_DLQ).build();

        Queue notificationQ = QueueBuilder.durable(NOTIFICATION_Q)
                .deadLetterExchange(RabbitMailConfig.DLX_EXCHANGE)
                .deadLetterRoutingKey(SCENARIO_RECALCULATED_RK)
                .build();
        Queue notificationDlq = QueueBuilder.durable(NOTIFICATION_DLQ).build();

        Binding recalcBinding = BindingBuilder.bind(recalcQ).to(events).with(OFFER_RATE_CHANGED_RK);
        Binding recalcDlqBinding = BindingBuilder.bind(recalcDlq).to(dlx).with(OFFER_RATE_CHANGED_RK);

        Binding notificationBinding = BindingBuilder.bind(notificationQ).to(events).with(SCENARIO_RECALCULATED_RK);
        Binding notificationDlqBinding = BindingBuilder.bind(notificationDlq).to(dlx).with(SCENARIO_RECALCULATED_RK);

        return new Declarables(recalcQ, recalcDlq, recalcBinding, recalcDlqBinding,
                notificationQ, notificationDlq, notificationBinding, notificationDlqBinding);
    }
}