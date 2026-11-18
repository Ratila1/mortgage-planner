package dev.nosurprise.common.outbox;

import dev.nosurprise.common.messaging.RabbitEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;

/**
 * Relay outbox → RabbitMQ (MICROLOANS.md §4.8). Каждую секунду берёт пачку неотправленных событий
 * под FOR UPDATE SKIP LOCKED, публикует с подтверждением брокера и только после него ставит published_at.
 * Гарантия at-least-once: если упадём между публикацией и коммитом, событие уйдёт повторно,
 * поэтому consumer-ы идемпотентны.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final RabbitEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository repository, RabbitEventPublisher publisher, ObjectMapper objectMapper,
                       OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Вызывается планировщиком через прокси, поэтому @Transactional работает.
     * Публичный, чтобы тесты могли прогнать relay синхронно. Возвращает число отправленных событий.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:1s}")
    @Transactional
    public int relayBatch() {
        List<OutboxEvent> batch = repository.lockUnpublished(properties.batchSize(), properties.maxAttempts());
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                // Payload уже JSON: отправляем дерево, конвертер сериализует его без двойного экранирования.
                // Listener получает свой record по типу параметра метода.
                JsonNode body = objectMapper.readTree(event.getPayload());
                publisher.publish(event.getEventType(), body);
                event.markPublished(clock.instant());
                published++;
            } catch (RuntimeException ex) {
                event.markFailed(ex.getMessage());
                if (event.getAttempts() >= properties.maxAttempts()) {
                    log.error("outbox event {} ({}) not published after {} attempts, left for investigation",
                            event.getEventId(), event.getEventType(), event.getAttempts(), ex);
                } else {
                    log.warn("outbox event {} ({}) publish failed, attempt {}",
                            event.getEventId(), event.getEventType(), event.getAttempts(), ex);
                }
            }
        }
        return published;
    }
}
