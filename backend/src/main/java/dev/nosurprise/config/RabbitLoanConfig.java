package dev.nosurprise.config;

import dev.nosurprise.loan.application.LoanRoutingKeys;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Очереди микрозаймов (MICROLOANS.md §4.5). У каждой очереди своя DLQ через domain.events.dlx.
 * notification.loan.q слушает несколько routing key, поэтому у неё фиксированный dead-letter key.
 */
@Configuration
public class RabbitLoanConfig {

    public static final String CONTRACT_Q = "loan.contract.q";
    public static final String CREDIT_HISTORY_Q = "loan.credit-history.q";
    public static final String NOTIFICATION_Q = "notification.loan.q";

    private static final String NOTIFICATION_DLQ_RK = "notification.loan";

    @Bean
    Declarables rabbitLoanDeclarables() {
        TopicExchange events = new TopicExchange(RabbitMailConfig.EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(RabbitMailConfig.DLX_EXCHANGE, true, false);
        List<Declarable> declarables = new ArrayList<>();

        addQueue(declarables, events, dlx, CONTRACT_Q, LoanRoutingKeys.APPROVED, List.of(LoanRoutingKeys.APPROVED));
        addQueue(declarables, events, dlx, CREDIT_HISTORY_Q, LoanRoutingKeys.HISTORY_CHANGED,
                List.of(LoanRoutingKeys.HISTORY_CHANGED));
        addQueue(declarables, events, dlx, NOTIFICATION_Q, NOTIFICATION_DLQ_RK, List.of(
                LoanRoutingKeys.CONTRACT_READY, LoanRoutingKeys.REJECTED,
                LoanRoutingKeys.SIGNED, LoanRoutingKeys.EXPIRED, LoanRoutingKeys.REPAID));
        return new Declarables(declarables);
    }

    private static void addQueue(List<Declarable> declarables, TopicExchange events, TopicExchange dlx,
                                 String name, String deadLetterKey, List<String> routingKeys) {
        Queue queue = QueueBuilder.durable(name)
                .deadLetterExchange(RabbitMailConfig.DLX_EXCHANGE)
                .deadLetterRoutingKey(deadLetterKey)
                .build();
        Queue dlq = QueueBuilder.durable(name + ".dlq").build();
        declarables.add(queue);
        declarables.add(dlq);
        for (String key : routingKeys) {
            Binding binding = BindingBuilder.bind(queue).to(events).with(key);
            declarables.add(binding);
        }
        declarables.add(BindingBuilder.bind(dlq).to(dlx).with(deadLetterKey));
    }
}
