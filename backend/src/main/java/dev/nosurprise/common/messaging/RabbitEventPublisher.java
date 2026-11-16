package dev.nosurprise.common.messaging;

import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Публикация событий в topic-exchange {@code domain.events} (§8). Вызывается ТОЛЬКО из
 * AFTER_COMMIT-слушателей (@TransactionalEventListener(phase = AFTER_COMMIT)),
 * чтобы consumer не видел старые данные.
 * <p>
 * К4: издатель теперь дожидается publisher-confirm от брокера перед возвратом.
 * При nack/return/тайм-ауте — повторная отправка, затем исключение (событие не теряется
 * бесследно: упавшая AFTER_COMMIT-синхронизация видна в логах, а идемпотентность
 * consumer'ов защищает от дублей при повторе). JSON-сериализация — JacksonJsonMessageConverter.
 */
@Component
public class RabbitEventPublisher {

    public static final String EVENTS_EXCHANGE = "domain.events";

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
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (waitForConfirm(routingKey, event)) {
                    return; // подтверждено брокером
                }
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        throw new IllegalStateException("event not confirmed by broker after " + MAX_ATTEMPTS + " attempts: "
                + "exchange=" + EVENTS_EXCHANGE + ", routingKey=" + routingKey, last);
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
        } catch (ExecutionException | TimeoutException ex) {
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
