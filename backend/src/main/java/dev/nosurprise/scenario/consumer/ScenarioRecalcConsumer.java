package dev.nosurprise.scenario.consumer;

import dev.nosurprise.offer.OfferNotFoundException;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.offer.event.OfferRateChanged;
import dev.nosurprise.scenario.Scenario;
import dev.nosurprise.scenario.ScenarioRepository;
import dev.nosurprise.scenario.ScenarioService;
import dev.nosurprise.scenario.event.ScenarioRecalculated;
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
import java.util.List;
import java.util.UUID;

@Component
public class ScenarioRecalcConsumer {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRecalcConsumer.class);

    static final String PROCESSED_KEY_PREFIX = "scenario:recalc:processed:";
    static final Duration PROCESSED_TTL = Duration.ofDays(7);

    private final ScenarioRepository scenarioRepository;
    private final ScenarioService scenarioService;
    private final OfferService offerService;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redis;

    public ScenarioRecalcConsumer(ScenarioRepository scenarioRepository, ScenarioService scenarioService,
                                 OfferService offerService, ApplicationEventPublisher eventPublisher,
                                 StringRedisTemplate redis) {
        this.scenarioRepository = scenarioRepository;
        this.scenarioService = scenarioService;
        this.offerService = offerService;
        this.eventPublisher = eventPublisher;
        this.redis = redis;
    }

    @RabbitListener(queues = "scenario.recalc.q")
    @Transactional
    public void on(OfferRateChanged event) {
        validate(event);

        String key = PROCESSED_KEY_PREFIX + event.eventId();
        if (isProcessed(key)) {
            log.info("scenario recalc event {} already processed, skipping", event.eventId());
            return;
        }

        try {
            offerService.findEntity(event.offerId());
        } catch (OfferNotFoundException ex) {
            log.warn("offer {} not found for recalc event {}, skipping", event.offerId(), event.eventId());
            markProcessed(key);
            return;
        }

        List<Scenario> scenarios = scenarioRepository.findAllByOfferId(event.offerId());
        for (Scenario scenario : scenarios) {
            var result = scenarioService.recalculate(scenario.getId(), event.newRate());
            UUID mailEventId = UUID.nameUUIDFromBytes(
                    (event.eventId() + ":" + scenario.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            eventPublisher.publishEvent(new ScenarioRecalculated(
                    mailEventId, Instant.now(), result.scenarioId(), result.calcId(), result.userId(),
                    result.oldRate(), result.newRate(), result.oldMonthlyPayment(),
                    result.newMonthlyPayment(), result.oldOverpayment(), result.newOverpayment()));
        }

        markProcessed(key);
    }

    private void validate(OfferRateChanged event) {
        if (event == null || event.eventId() == null || event.offerId() == null || event.newRate() == null) {
            throw new IllegalArgumentException("invalid OfferRateChanged event");
        }
    }

    private boolean isProcessed(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key)) || redis.opsForValue().get(key) != null;
    }

    private void markProcessed(String key) {
        redis.opsForValue().setIfAbsent(key, "1", PROCESSED_TTL);
    }
}