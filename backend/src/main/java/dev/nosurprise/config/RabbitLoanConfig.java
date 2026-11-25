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
    /** Отдельная очередь авто-подписи: заявки, подписанные при подаче (V20). */
    public static final String SIGN_AUTO_Q = "loan.sign-auto.q";
    /** Результаты скоринга из credit-policy (CREDIT_POLICY.md §6.2). */
    public static final String SCORING_RESULT_Q = "loan.scoring-result.q";
    /**
     * Очередь запросов скоринга принадлежит credit-policy, но объявляем её и здесь (с теми же параметрами):
     * брокер без volume теряет очереди при перезапуске, и запрос, отправленный до старта credit-policy,
     * иначе ушёл бы в никуда. Объявление идемпотентно.
     */
    public static final String SCORING_REQUEST_Q = "credit-policy.scoring-request.q";
    private static final String SCORING_REQUEST_DLQ_RK = "credit-policy.scoring-request";

    private static final String NOTIFICATION_DLQ_RK = "notification.loan";
    private static final String SIGN_AUTO_DLQ_RK = "loan.sign-auto";

    @Bean
    Declarables rabbitLoanDeclarables() {
        TopicExchange events = new TopicExchange(RabbitMailConfig.EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(RabbitMailConfig.DLX_EXCHANGE, true, false);
        List<Declarable> declarables = new ArrayList<>();

        addQueue(declarables, events, dlx, CONTRACT_Q, LoanRoutingKeys.APPROVED, List.of(LoanRoutingKeys.APPROVED));
        addQueue(declarables, events, dlx, CREDIT_HISTORY_Q, LoanRoutingKeys.HISTORY_CHANGED,
                List.of(LoanRoutingKeys.HISTORY_CHANGED));
        addQueue(declarables, events, dlx, SIGN_AUTO_Q, SIGN_AUTO_DLQ_RK, List.of(LoanRoutingKeys.CONTRACT_READY));
        addQueue(declarables, events, dlx, NOTIFICATION_Q, NOTIFICATION_DLQ_RK, List.of(
                LoanRoutingKeys.CONTRACT_READY, LoanRoutingKeys.REJECTED,
                LoanRoutingKeys.SIGNED, LoanRoutingKeys.EXPIRED, LoanRoutingKeys.REPAID, LoanRoutingKeys.OVERDUE));
        addQueue(declarables, events, dlx, SCORING_RESULT_Q, LoanRoutingKeys.SCORING_COMPLETED,
                List.of(LoanRoutingKeys.SCORING_COMPLETED));
        addQueue(declarables, events, dlx, SCORING_REQUEST_Q, SCORING_REQUEST_DLQ_RK,
                List.of(LoanRoutingKeys.SCORING_REQUESTED));
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
