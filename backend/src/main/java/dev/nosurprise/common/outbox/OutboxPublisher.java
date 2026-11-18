package dev.nosurprise.common.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Единственный способ отправить доменное событие из бизнес-кода модулей микрозаймов (MICROLOANS.md §4.8).
 * Событие не уходит в RabbitMQ сразу, а пишется в outbox_events в текущей транзакции:
 * либо коммитятся и данные, и событие, либо ничего. Отправку делает {@link OutboxRelay}.
 */
@Component
public class OutboxPublisher {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * MANDATORY: вызов вне транзакции — ошибка. Иначе событие записалось бы отдельно от данных,
     * и мы вернулись бы к проблеме, которую решает outbox.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String routingKey, String aggregateType, Object aggregateId, OutboxMessage message) {
        String payload = objectMapper.writeValueAsString(message);
        repository.save(new OutboxEvent(message.eventId(), aggregateType, String.valueOf(aggregateId),
                routingKey, payload, clock.instant()));
    }
}
