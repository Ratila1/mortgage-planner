package dev.nosurprise.policy.config;

import dev.nosurprise.policy.scoring.ScoringCompleted;
import dev.nosurprise.policy.scoring.ScoringRequested;
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
 * RabbitMQ (CREDIT_POLICY.md §8): тот же topic-exchange domain.events и DLX, что у монолита
 * (объявление идемпотентно, параметры совпадают). Своя очередь запросов скоринга с DLQ и, для надёжности,
 * очередь результатов монолита.
 */
@Configuration
public class RabbitConfig {

    public static final String EVENTS_EXCHANGE = "domain.events";
    public static final String DLX_EXCHANGE = "domain.events.dlx";
    public static final String SCORING_REQUEST_Q = "credit-policy.scoring-request.q";
    private static final String SCORING_REQUEST_DLQ_RK = "credit-policy.scoring-request";
    /** Очередь результатов принадлежит монолиту; объявляем её и здесь с теми же параметрами (см. ниже). */
    public static final String SCORING_RESULT_Q = "loan.scoring-result.q";

    @Bean
    Declarables creditPolicyDeclarables() {
        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX_EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(SCORING_REQUEST_Q)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(SCORING_REQUEST_DLQ_RK)
                .build();
        Queue dlq = QueueBuilder.durable(SCORING_REQUEST_Q + ".dlq").build();
        Binding binding = BindingBuilder.bind(queue).to(events).with(ScoringRequested.ROUTING_KEY);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(SCORING_REQUEST_DLQ_RK);
        // Брокер без volume теряет очереди при перезапуске: если монолит ещё не стартовал, результат иначе
        // ушёл бы в никуда. Параметры совпадают с RabbitLoanConfig монолита — объявление идемпотентно.
        Queue results = QueueBuilder.durable(SCORING_RESULT_Q)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(ScoringCompleted.ROUTING_KEY)
                .build();
        Queue resultsDlq = QueueBuilder.durable(SCORING_RESULT_Q + ".dlq").build();
        Binding resultsBinding = BindingBuilder.bind(results).to(events).with(ScoringCompleted.ROUTING_KEY);
        Binding resultsDlqBinding = BindingBuilder.bind(resultsDlq).to(dlx).with(ScoringCompleted.ROUTING_KEY);
        return new Declarables(events, dlx, queue, dlq, binding, dlqBinding, results, resultsDlq, resultsBinding,
                resultsDlqBinding);
    }

    /** JSON в обе стороны; тип входящего сообщения берётся из параметра метода-слушателя. */
    @Bean
    MessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
