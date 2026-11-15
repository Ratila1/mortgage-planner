package dev.nosurprise.common.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * К4: publisher-confirm + returns-callback с ретраями.
 * Событие подтверждено брокером → успех. Nack/return/таймаут → после MAX_ATTEMPTS исключение.
 */
class RabbitEventPublisherTest {

    private static final String ROUTING_KEY = "offer.rate.changed";
    private static final Object EVENT = new Object() {};

    private RabbitTemplate rabbitTemplate;
    private RabbitTemplate.ConfirmCallback confirmCallback;
    private RabbitTemplate.ReturnsCallback returnsCallback;
    private RabbitEventPublisher publisher;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        // Перехватываем колбэки, которые конструктор RabbitEventPublisher устанавливает через
        // setConfirmCallback / setReturnsCallback на моке RabbitTemplate.
        doAnswer(inv -> {
            confirmCallback = inv.getArgument(0, RabbitTemplate.ConfirmCallback.class);
            return null;
        }).when(rabbitTemplate).setConfirmCallback(any(RabbitTemplate.ConfirmCallback.class));
        doAnswer(inv -> {
            returnsCallback = inv.getArgument(0, RabbitTemplate.ReturnsCallback.class);
            return null;
        }).when(rabbitTemplate).setReturnsCallback(any(RabbitTemplate.ReturnsCallback.class));

        publisher = new RabbitEventPublisher(rabbitTemplate);
    }

    @Test
    @DisplayName("ack от брокера → publish возвращает успешно с первой попытки")
    void ackFirstAttempt() throws Exception {
        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3, CorrelationData.class);
            confirmCallback.confirm(cd, true, null);
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitEventPublisher.EVENTS_EXCHANGE), eq(ROUTING_KEY), any(Object.class), any(CorrelationData.class));

        publisher.publish(ROUTING_KEY, EVENT);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitEventPublisher.EVENTS_EXCHANGE), eq(ROUTING_KEY), any(Object.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("nack от брокера → 3 попытки, затем IllegalStateException")
    void nackRetriesThenException() {
        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3, CorrelationData.class);
            confirmCallback.confirm(cd, false, "no route");
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitEventPublisher.EVENTS_EXCHANGE), eq(ROUTING_KEY), any(Object.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish(ROUTING_KEY, EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event not confirmed by broker after 3 attempts");
    }

    @Test
    @DisplayName("returned message → future.complete(false) → ретрай, затем исключение")
    void returnedMessageRetriesThenException() {
        doAnswer(inv -> {
            returnsCallback.returnedMessage(new ReturnedMessage(null, 0, "", "", ""));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitEventPublisher.EVENTS_EXCHANGE), eq(ROUTING_KEY), any(Object.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish(ROUTING_KEY, EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event not confirmed by broker after 3 attempts");
    }

    @Test
    @DisplayName("таймаут confirm → IllegalStateException")
    void confirmTimeoutThrows() {
        doAnswer(inv -> {
            // не вызываем колбэк → future.get уйдёт в таймаут (5 сек)
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitEventPublisher.EVENTS_EXCHANGE), eq(ROUTING_KEY), any(Object.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish(ROUTING_KEY, EVENT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ack на второй попытке после nack на первой → успех")
    void ackOnSecondAttempt() throws Exception {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();

        doAnswer(inv -> {
            int count = callCount.incrementAndGet();
            CorrelationData cd = inv.getArgument(3, CorrelationData.class);
            // Первая попытка — nack, вторая — ack
            if (count == 1) {
                confirmCallback.confirm(cd, false, "retry");
            } else {
                confirmCallback.confirm(cd, true, null);
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitEventPublisher.EVENTS_EXCHANGE), eq(ROUTING_KEY), any(Object.class), any(CorrelationData.class));

        publisher.publish(ROUTING_KEY, EVENT);

        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(RabbitEventPublisher.EVENTS_EXCHANGE), eq(ROUTING_KEY), any(Object.class), any(CorrelationData.class));
    }
}