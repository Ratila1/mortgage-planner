package dev.nosurprise.notification;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.offer.OfferNotFoundException;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.offer.event.OfferRateChanged;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OfferRateChangedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OfferRateChangedConsumer.class);

    static final String PROCESSED_KEY_PREFIX = "notification:offer-rate-changed:processed:";
    static final Duration PROCESSED_TTL = Duration.ofDays(7);

    private final UserService userService;
    private final OfferService offerService;
    private final RabbitEventPublisher publisher;
    private final StringRedisTemplate redis;
    private final String from;

    public OfferRateChangedConsumer(UserService userService, OfferService offerService,
                                   RabbitEventPublisher publisher, StringRedisTemplate redis,
                                   @Value("${app.mail.from}") String from) {
        this.userService = userService;
        this.offerService = offerService;
        this.publisher = publisher;
        this.redis = redis;
        this.from = from;
    }

    @RabbitListener(queues = "notification.offer-rate-changed.q")
    public void on(OfferRateChanged event) {
        validate(event);

        String key = PROCESSED_KEY_PREFIX + event.eventId();
        if (isProcessed(key)) {
            log.info("offer rate changed event {} already processed, skipping", event.eventId());
            return;
        }

        String bankName;
        try {
            bankName = offerService.findEntity(event.offerId()).getBankName();
        } catch (OfferNotFoundException ex) {
            log.warn("offer {} not found for rate changed event {}, skipping", event.offerId(), event.eventId());
            markProcessed(key);
            return;
        }

        List<UserEntity> users = userService.findAllActiveVerified();
        for (UserEntity user : users) {
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                continue;
            }
            UUID mailEventId = UUID.nameUUIDFromBytes(
                    (event.eventId() + ":" + user.getId()).getBytes(StandardCharsets.UTF_8));
            String subject = "Изменение ставки банковского предложения";
            String body = "Здравствуйте!\n\n"
                    + "Ставка по предложению банка \"" + (bankName == null ? "банка" : bankName) + "\" изменилась:\n"
                    + "было: " + event.oldRate().toPlainString() + "%\n"
                    + "стало: " + event.newRate().toPlainString() + "%\n\n"
                    + "С уважением,\nкоманда сервиса";
            publisher.publish("mail.requested", new MailRequested(
                    mailEventId, Instant.now(), user.getEmail(), subject, body));
        }

        markProcessed(key);
    }

    private void validate(OfferRateChanged event) {
        if (event == null || event.eventId() == null || event.offerId() == null
                || event.oldRate() == null || event.newRate() == null) {
            throw new IllegalArgumentException("invalid OfferRateChanged event");
        }
    }

    private boolean isProcessed(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key))
                    || redis.opsForValue().get(key) != null;
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    private void markProcessed(String key) {
        redis.opsForValue().setIfAbsent(key, "1", PROCESSED_TTL);
    }
}