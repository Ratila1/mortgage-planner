package dev.nosurprise.common.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Публикация событий в topic-exchange {@code domain.events} (§8). Отправка выполняется
 * асинхронно: HTTP-запрос не ждёт publisher-confirm от брокера, чтобы не блокировать
 * пользователя (например, при регистрации). Ошибки логируются.
 */
@Component
public class RabbitEventPublisher {

    public static final String EVENTS_EXCHANGE = "domain.events";

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 3;

    private final RabbitTemplate rabbitTemplate;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> confirms = new ConcurrentHashMap<>();

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        rabbitTemplate.setConfirmCallback(this::handleConfirm);
        rabbitTemplate.setReturnsCallback(this::handleReturn);
    }

    public void publish(String routingKey, Object event) {
        CompletableFuture.runAsync(() -> {
            RuntimeException last = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    if (waitForConfirm(routingKey, event)) {
                        return;
                    }
                } catch (RuntimeException ex) {
                    last = ex;
                }
            }
            log.error("event not confirmed by broker after {} attempts: exchange={}, routingKey={}",
                    MAX_ATTEMPTS, EVENTS_EXCHANGE, routingKey, last);
        });
    }

    private boolean waitForConfirm(String routingKey, Object event) {
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        confirms.put(correlationData.getId(), future);
        try {
            rabbitTemplate.convertAndSend(EVENTS_EXCHANGE, routingKey, event, correlationData);
            Boolean ack = future.get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(ack);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("event publish interrupted: " + routingKey, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("event publish not confirmed within timeout: " + routingKey, ex);
        } finally {
            confirms.remove(correlationData.getId());
        }
    }

    private void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        String id = correlationData == null ? null : correlationData.getId();
        CompletableFuture<Boolean> future = id == null ? null : confirms.remove(id);
        if (future != null) {
            future.complete(ack);
        }
    }

    private void handleReturn(ReturnedMessage returned) {
        String id = returned.getMessage().getMessageProperties().getCorrelationId();
        CompletableFuture<Boolean> future = id == null ? null : confirms.remove(id);
        if (future != null) {
            future.complete(false);
        }
    }
}