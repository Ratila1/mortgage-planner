package com.bank.mortgage.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Value("${rabbitmq.exchange.rate-changed}")
    private String rateChangedExchange;

    @Value("${rabbitmq.queue.rate-changed}")
    private String rateChangedQueue;

    @Value("${rabbitmq.queue.rate-changed-dlq}")
    private String rateChangedDlq;

    @Value("${rabbitmq.queue.rate-changed-retry}")
    private String rateChangedRetryQueue;

    @Value("${rabbitmq.exchange.rate-changed-retry}")
    private String rateChangedRetryExchange;

    @Value("${rabbitmq.routing-key.rate-changed}")
    private String rateChangedRoutingKey;

    @Value("${rabbitmq.routing-key.rate-changed-retry}")
    private String rateChangedRetryRoutingKey;

    @Value("${rabbitmq.exchange.pdf-export}")
    private String pdfExportExchange;

    @Value("${rabbitmq.queue.pdf-export}")
    private String pdfExportQueue;

    @Value("${rabbitmq.queue.pdf-export-dlq}")
    private String pdfExportDlq;

    @Value("${rabbitmq.queue.pdf-export-retry}")
    private String pdfExportRetryQueue;

    @Value("${rabbitmq.exchange.pdf-export-retry}")
    private String pdfExportRetryExchange;

    @Value("${rabbitmq.routing-key.pdf-export}")
    private String pdfExportRoutingKey;

    @Value("${rabbitmq.routing-key.pdf-export-retry}")
    private String pdfExportRetryRoutingKey;

    @Value("${rabbitmq.retry.max-attempts:5}")
    private int maxRetryAttempts;

    @Value("${rabbitmq.retry.base-delay-ms:2000}")
    private int baseDelayMs;

    // Rate Changed Exchange & Queue with DLQ and Retry
    @Bean
    public DirectExchange rateChangedExchange() {
        return new DirectExchange(rateChangedExchange, true, false);
    }

    @Bean
    public Queue rateChangedDlq() {
        return QueueBuilder.durable(rateChangedDlq).build();
    }

    @Bean
    public DirectExchange rateChangedDlx() {
        return new DirectExchange(rateChangedDlq + ".dlx", true, false);
    }

    @Bean
    public Binding rateChangedDlqBinding() {
        return BindingBuilder.bind(rateChangedDlq()).to(rateChangedDlx()).with(rateChangedDlq);
    }

    @Bean
    public DirectExchange rateChangedRetryExchange() {
        return new DirectExchange(rateChangedRetryExchange, true, false);
    }

    @Bean
    public Queue rateChangedRetryQueue() {
        // TTL will be set per message via x-delay header or calculated on republish
        // Queue TTL is max delay to prevent infinite retention
        return QueueBuilder.durable(rateChangedRetryQueue)
                .withArgument("x-dead-letter-exchange", rateChangedExchange)
                .withArgument("x-dead-letter-routing-key", rateChangedRoutingKey)
                .withArgument("x-message-ttl", calculateMaxTtl())
                .build();
    }

    @Bean
    public Binding rateChangedRetryBinding() {
        return BindingBuilder.bind(rateChangedRetryQueue()).to(rateChangedRetryExchange()).with(rateChangedRetryRoutingKey);
    }

    @Bean
    public Queue rateChangedQueue() {
        return QueueBuilder.durable(rateChangedQueue)
                .withArgument("x-dead-letter-exchange", rateChangedDlx().getName())
                .withArgument("x-dead-letter-routing-key", rateChangedDlq)
                .build();
    }

    @Bean
    public Binding rateChangedBinding() {
        return BindingBuilder.bind(rateChangedQueue()).to(rateChangedExchange()).with(rateChangedRoutingKey);
    }

    // PDF Export Exchange & Queue with DLQ and Retry
    @Bean
    public DirectExchange pdfExportExchange() {
        return new DirectExchange(pdfExportExchange, true, false);
    }

    @Bean
    public Queue pdfExportDlq() {
        return QueueBuilder.durable(pdfExportDlq).build();
    }

    @Bean
    public DirectExchange pdfExportDlx() {
        return new DirectExchange(pdfExportDlq + ".dlx", true, false);
    }

    @Bean
    public Binding pdfExportDlqBinding() {
        return BindingBuilder.bind(pdfExportDlq()).to(pdfExportDlx()).with(pdfExportDlq);
    }

    @Bean
    public DirectExchange pdfExportRetryExchange() {
        return new DirectExchange(pdfExportRetryExchange, true, false);
    }

    @Bean
    public Queue pdfExportRetryQueue() {
        return QueueBuilder.durable(pdfExportRetryQueue)
                .withArgument("x-dead-letter-exchange", pdfExportExchange)
                .withArgument("x-dead-letter-routing-key", pdfExportRoutingKey)
                .withArgument("x-message-ttl", calculateMaxTtl())
                .build();
    }

    @Bean
    public Binding pdfExportRetryBinding() {
        return BindingBuilder.bind(pdfExportRetryQueue()).to(pdfExportRetryExchange()).with(pdfExportRetryRoutingKey);
    }

    @Bean
    public Queue pdfExportQueue() {
        return QueueBuilder.durable(pdfExportQueue)
                .withArgument("x-dead-letter-exchange", pdfExportDlx().getName())
                .withArgument("x-dead-letter-routing-key", pdfExportDlq)
                .build();
    }

    @Bean
    public Binding pdfExportBinding() {
        return BindingBuilder.bind(pdfExportQueue()).to(pdfExportExchange()).with(pdfExportRoutingKey);
    }

    private long calculateMaxTtl() {
        // Max TTL for exponential backoff: baseDelay * 2^(maxAttempts-1)
        // Add some buffer
        return (long) (baseDelayMs * Math.pow(2, maxRetryAttempts - 1) * 1.5);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        return template;
    }
}