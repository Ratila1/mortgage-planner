package dev.nosurprise.scenario.consumer;

public class ScenarioRecalculationConsumer extends ScenarioRecalcConsumer {
    public ScenarioRecalculationConsumer(dev.nosurprise.scenario.ScenarioRepository scenarioRepository,
                                        dev.nosurprise.scenario.ScenarioService scenarioService,
                                        dev.nosurprise.offer.OfferService offerService,
                                        org.springframework.context.ApplicationEventPublisher eventPublisher,
                                        org.springframework.data.redis.core.StringRedisTemplate redis) {
        super(scenarioRepository, scenarioService, offerService, eventPublisher, redis);
    }
}