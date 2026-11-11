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
public class RabbitPdfConfig {

    public static final String EXPORT_PDF_Q = "export.pdf.q";
    public static final String EXPORT_PDF_DLQ = "export.pdf.q.dlq";
    public static final String EXPORT_PDF_REQUESTED_RK = "export.pdf.requested";

    @Bean
    Declarables rabbitPdfDeclarables() {
        TopicExchange events = new TopicExchange(RabbitMailConfig.EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(RabbitMailConfig.DLX_EXCHANGE, true, false);

        Queue queue = QueueBuilder.durable(EXPORT_PDF_Q)
                .deadLetterExchange(RabbitMailConfig.DLX_EXCHANGE)
                .deadLetterRoutingKey(EXPORT_PDF_REQUESTED_RK)
                .build();
        Queue dlq = QueueBuilder.durable(EXPORT_PDF_DLQ).build();

        Binding binding = BindingBuilder.bind(queue).to(events).with(EXPORT_PDF_REQUESTED_RK);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(EXPORT_PDF_REQUESTED_RK);

        return new Declarables(queue, dlq, binding, dlqBinding);
    }
}