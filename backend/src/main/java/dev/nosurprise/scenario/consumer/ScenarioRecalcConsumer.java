package dev.nosurprise.scenario.consumer;

import dev.nosurprise.common.idempotency.IdempotencyGuard;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.config.RabbitScenarioConfig;
import dev.nosurprise.offer.BankOffer;
import dev.nosurprise.offer.OfferNotFoundException;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.offer.event.OfferRateChanged;
import dev.nosurprise.scenario.Scenario;
import dev.nosurprise.scenario.ScenarioRepository;
import dev.nosurprise.scenario.ScenarioService;
import dev.nosurprise.scenario.ScenarioRecalculationResult;
import dev.nosurprise.scenario.event.ScenarioRecalculated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Пересчёт сценариев при изменении ставки оффера (§8):
 * - ставка берётся из текущего состояния оффера, событие — только триггер;
 *   устаревшее событие (ставка в событии != ставка в БД) пропускается —
 *   актуальное состояние придёт своим событием;
 * - каждый сценарий пересчитывается в своей транзакции: ошибка одного
 *   не откатывает пачку и не роняет остальных в DLQ;
 * - идемпотентность по eventId: claim (SET NX) до работы, complete после —
 *   повторная доставка того же eventId не создаёт дубли.
 */
@Component
public class ScenarioRecalcConsumer {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRecalcConsumer.class);

    static final String PROCESSED_KEY_PREFIX = "scenario:recalc:processed:";

    private final ScenarioRepository scenarioRepository;
    private final ScenarioService scenarioService;
    private final OfferService offerService;
    private final RabbitEventPublisher rabbitPublisher;
    private final IdempotencyGuard guard;

    public ScenarioRecalcConsumer(ScenarioRepository scenarioRepository, ScenarioService scenarioService,
                                  OfferService offerService, RabbitEventPublisher rabbitPublisher,
                                  IdempotencyGuard guard) {
        this.scenarioRepository = scenarioRepository;
        this.scenarioService = scenarioService;
        this.offerService = offerService;
        this.rabbitPublisher = rabbitPublisher;
        this.guard = guard;
    }

    @RabbitListener(queues = "scenario.recalc.q")
    public void on(OfferRateChanged event) {
        validate(event);

        String key = PROCESSED_KEY_PREFIX + event.eventId();
        if (guard.isDone(key)) {
            log.info("scenario recalc event {} already processed, skipping", event.eventId());
            return;
        }
        if (!guard.tryClaim(key)) {
            log.info("scenario recalc event {} is being processed elsewhere, skipping", event.eventId());
            return;
        }
        try {
            BankOffer offer = offerService.findEntity(event.offerId());
            // compareTo, а не equals: BigDecimal.equals чувствителен к scale
            // (numeric(5,2) из БД всегда scale 2, событие может нести scale 1 — "8.6" vs "8.60").
            if (offer.getRate() == null || offer.getRate().compareTo(event.newRate()) != 0) {
                // Событие устарело: ставка оффера уже другая. Актуальное состояние
                // придёт (или уже пришло) своим событием — повторять не нужно.
                log.info("stale rate-changed event {}: offer rate is {}, event rate is {}, skipping",
                        event.eventId(), offer.getRate(), event.newRate());
                guard.complete(key);
                return;
            }

            List<Scenario> scenarios = scenarioRepository.findAllByOfferId(event.offerId());
            for (Scenario scenario : scenarios) {
                // recalculate открывает собственную транзакцию: сбой одного сценария
                // не влияет на остальные (пачка не уходит в DLQ целиком).
                try {
                    ScenarioRecalculationResult result = scenarioService.recalculate(scenario.getId(), offer.getRate());
                    UUID mailEventId = UUID.nameUUIDFromBytes(
                            (event.eventId() + ":" + scenario.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    rabbitPublisher.publish(RabbitScenarioConfig.SCENARIO_RECALCULATED_RK, new ScenarioRecalculated(
                            mailEventId, Clock.systemUTC().instant(), result.scenarioId(), result.calcId(), result.userId(),
                            result.oldRate(), result.newRate(), result.oldMonthlyPayment(),
                            result.newMonthlyPayment(), result.oldOverpayment(), result.newOverpayment()));
                } catch (RuntimeException ex) {
                    log.error("failed to recalculate scenario {} for offer {}, continuing with the rest",
                            scenario.getId(), event.offerId(), ex);
                }
            }

            guard.complete(key);
        } catch (OfferNotFoundException ex) {
            log.warn("offer {} not found for recalc event {}, skipping", event.offerId(), event.eventId());
            guard.complete(key);
        } catch (RuntimeException ex) {
            guard.release(key);
            throw ex;
        }
    }

    private void validate(OfferRateChanged event) {
        if (event == null || event.eventId() == null || event.offerId() == null || event.newRate() == null) {
            throw new IllegalArgumentException("invalid OfferRateChanged event");
        }
    }
}
