package dev.nosurprise.notification.consumer;

import dev.nosurprise.notification.NotificationService;
import dev.nosurprise.notification.event.ScenarioRecalculatedMailRequested;
import dev.nosurprise.notification.event.ScenarioRecalculatedMailBridge;
import dev.nosurprise.offer.OfferNotFoundException;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.scenario.Scenario;
import dev.nosurprise.scenario.ScenarioService;
import dev.nosurprise.scenario.event.ScenarioRecalculated;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class ScenarioRecalculatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRecalculatedConsumer.class);

    static final String PROCESSED_KEY_PREFIX = "notification:scenario-recalculated:processed:";
    static final Duration PROCESSED_TTL = Duration.ofDays(7);

    private final ScenarioService scenarioService;
    private final OfferService offerService;
    private final UserService userService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redis;

    public ScenarioRecalculatedConsumer(ScenarioService scenarioService, OfferService offerService,
                                       UserService userService, NotificationService notificationService,
                                       ApplicationEventPublisher eventPublisher, StringRedisTemplate redis) {
        this.scenarioService = scenarioService;
        this.offerService = offerService;
        this.userService = userService;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.redis = redis;
    }

    @RabbitListener(queues = "notification.q")
    @Transactional
    public void on(ScenarioRecalculated event) {
        validate(event);

        String key = PROCESSED_KEY_PREFIX + event.eventId();
        if (isProcessed(key)) {
            log.info("scenario recalculated event {} already processed, skipping", event.eventId());
            return;
        }

        var scenarioOpt = scenarioService.findByIdAndUserId(event.scenarioId(), event.userId());
        if (scenarioOpt.isEmpty()) {
            log.warn("scenario {} not found for recalculated event {}, skipping", event.scenarioId(), event.eventId());
            markProcessed(key);
            return;
        }
        Scenario scenario = scenarioOpt.get();

        UserEntity user = userService.findById(event.userId()).orElse(null);
        if (user == null || !user.isEmailVerified() || user.getEmail() == null || user.getEmail().isBlank()) {
            log.info("user {} is not eligible for recalculated notification {}, skipping", event.userId(), event.eventId());
            markProcessed(key);
            return;
        }

        String bankName = "банковское предложение";
        if (scenario.getOfferId() != null) {
            try {
                bankName = offerService.findEntity(scenario.getOfferId()).getBankName();
            } catch (OfferNotFoundException ex) {
                log.warn("offer {} not found for scenario {}, using generic bank name", scenario.getOfferId(), scenario.getId());
            }
        }

        notificationService.createScenarioRecalculated(
                event.userId(), event.scenarioId(), event.calcId(),
                event.oldRate(), event.newRate(),
                event.oldMonthlyPayment(), event.newMonthlyPayment(),
                event.oldOverpayment(), event.newOverpayment());

        String subject = "Изменение ставки банковского предложения";
        String body = "Здравствуйте!\n\n"
                + "Ставка по предложению банка \"" + bankName + "\" изменилась:\n"
                + "было: " + event.oldRate().toPlainString() + "%, платёж " + money(event.oldMonthlyPayment())
                + ", переплата " + money(event.oldOverpayment()) + "\n"
                + "стало: " + event.newRate().toPlainString() + "%, платёж " + money(event.newMonthlyPayment())
                + ", переплата " + money(event.newOverpayment()) + "\n\n"
                + "С уважением,\nкоманда сервиса";

        UUID mailEventId = UUID.nameUUIDFromBytes((event.eventId() + ":" + event.userId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        eventPublisher.publishEvent(new ScenarioRecalculatedMailRequested(
                mailEventId, Instant.now(), user.getEmail(), subject, body));

        markProcessed(key);
    }

    private void validate(ScenarioRecalculated event) {
        if (event == null || event.eventId() == null || event.scenarioId() == null
                || event.calcId() == null || event.userId() == null) {
            throw new IllegalArgumentException("invalid ScenarioRecalculated event");
        }
    }

    private boolean isProcessed(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key)) || redis.opsForValue().get(key) != null;
    }

    private void markProcessed(String key) {
        redis.opsForValue().setIfAbsent(key, "1", PROCESSED_TTL);
    }

    private static String money(BigDecimal value) {
        return value == null ? "0.00" : value.toPlainString();
    }
}