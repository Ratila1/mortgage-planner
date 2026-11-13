package dev.nosurprise.notification;

import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.offer.OfferNotFoundException;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.offer.event.OfferRateChanged;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfferRateChangedConsumerTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID offerId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("валидное событие публикует mail.requested для подходящих пользователей")
    void publishesMailRequests() {
        UserService userService = mock(UserService.class);
        OfferService offerService = mock(OfferService.class);
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.hasKey("notification:offer-rate-changed:processed:" + eventId)).thenReturn(false);
        when(ops.get("notification:offer-rate-changed:processed:" + eventId)).thenReturn(null);
        when(ops.setIfAbsent(any(), any(), any())).thenReturn(true);

        UserEntity user = new UserEntity(userId, "user@example.com", "hash", true,
                dev.nosurprise.user.Provider.LOCAL, null, Instant.EPOCH, java.util.Set.of(dev.nosurprise.user.Role.USER));
        when(userService.findAllActiveVerified()).thenReturn(List.of(user));
        dev.nosurprise.offer.BankOffer offer = mock(dev.nosurprise.offer.BankOffer.class);
        when(offer.getBankName()).thenReturn("Test Bank");
        when(offerService.findEntity(offerId)).thenReturn(offer);

        OfferRateChangedConsumer consumer = new OfferRateChangedConsumer(userService, offerService, publisher, redis, "from@example.com");
        consumer.on(new OfferRateChanged(eventId, Instant.now(), offerId, new BigDecimal("9.50"), new BigDecimal("10.00")));

        verify(publisher).publish(eq("mail.requested"), any());
        verify(ops).setIfAbsent(eq("notification:offer-rate-changed:processed:" + eventId), eq("1"), any());
    }

    @Test
    @DisplayName("повторный eventId полностью пропускается")
    void duplicateSkipped() {
        UserService userService = mock(UserService.class);
        OfferService offerService = mock(OfferService.class);
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("notification:offer-rate-changed:processed:" + eventId)).thenReturn(true);

        OfferRateChangedConsumer consumer = new OfferRateChangedConsumer(userService, offerService, publisher, redis, "from@example.com");
        consumer.on(new OfferRateChanged(eventId, Instant.now(), offerId, new BigDecimal("9.50"), new BigDecimal("10.00")));

        verify(publisher, never()).publish(any(), any());
        verify(userService, never()).findAllActiveVerified();
    }

    @Test
    @DisplayName("невалидное событие выбрасывает исключение и не публикует mail")
    void invalidEventThrows() {
        UserService userService = mock(UserService.class);
        OfferService offerService = mock(OfferService.class);
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        OfferRateChangedConsumer consumer = new OfferRateChangedConsumer(userService, offerService, publisher, redis, "from@example.com");

        assertThatThrownBy(() -> consumer.on(new OfferRateChanged(null, Instant.now(), offerId, new BigDecimal("9.50"), new BigDecimal("10.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("offer не найден: логируется, событие отмечается обработанным")
    void missingOfferSkipped() {
        UserService userService = mock(UserService.class);
        OfferService offerService = mock(OfferService.class);
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.hasKey("notification:offer-rate-changed:processed:" + eventId)).thenReturn(false);
        when(ops.get("notification:offer-rate-changed:processed:" + eventId)).thenReturn(null);
        when(ops.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(offerService.findEntity(offerId)).thenThrow(new OfferNotFoundException("not found"));

        OfferRateChangedConsumer consumer = new OfferRateChangedConsumer(userService, offerService, publisher, redis, "from@example.com");
        consumer.on(new OfferRateChanged(eventId, Instant.now(), offerId, new BigDecimal("9.50"), new BigDecimal("10.00")));

        verify(publisher, never()).publish(any(), any());
        verify(ops).setIfAbsent(eq("notification:offer-rate-changed:processed:" + eventId), eq("1"), any());
    }

    @Test
    @DisplayName("сбой публикации mail не отмечает событие обработанным")
    void publishFailureDoesNotMarkProcessed() {
        UserService userService = mock(UserService.class);
        OfferService offerService = mock(OfferService.class);
        RabbitEventPublisher publisher = mock(RabbitEventPublisher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.hasKey("notification:offer-rate-changed:processed:" + eventId)).thenReturn(false);
        when(ops.get("notification:offer-rate-changed:processed:" + eventId)).thenReturn(null);

        UserEntity user = new UserEntity(userId, "user@example.com", "hash", true,
                dev.nosurprise.user.Provider.LOCAL, null, Instant.EPOCH, java.util.Set.of(dev.nosurprise.user.Role.USER));
        when(userService.findAllActiveVerified()).thenReturn(List.of(user));
        dev.nosurprise.offer.BankOffer offer = mock(dev.nosurprise.offer.BankOffer.class);
        when(offer.getBankName()).thenReturn("Test Bank");
        when(offerService.findEntity(offerId)).thenReturn(offer);
        org.mockito.Mockito.doThrow(new RuntimeException("rabbit down"))
                .when(publisher).publish(eq("mail.requested"), any());

        OfferRateChangedConsumer consumer = new OfferRateChangedConsumer(userService, offerService, publisher, redis, "from@example.com");

        assertThatThrownBy(() -> consumer.on(new OfferRateChanged(eventId, Instant.now(), offerId, new BigDecimal("9.50"), new BigDecimal("10.00"))))
                .isInstanceOf(RuntimeException.class);
        verify(ops, never()).setIfAbsent(any(), any(), any());
    }
}