package dev.nosurprise.common.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Публикация событий в topic-exchange domain.events (§8). Вызывается ТОЛЬКО из
 * AFTER_COMMIT-слушателей (@TransactionalEventListener(phase = AFTER_COMMIT)),
 * чтобы consumer не видел старые данные.
 * JSON-сериализация — JacksonJsonMessageConverter (RabbitMailConfig).
 */
@Component
public class RabbitEventPublisher {

    public static final String EVENTS_EXCHANGE = "domain.events";

    private final RabbitTemplate rabbitTemplate;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String routingKey, Object event) {
        rabbitTemplate.convertAndSend(EVENTS_EXCHANGE, routingKey, event);
    }
}
