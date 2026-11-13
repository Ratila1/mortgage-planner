package dev.nosurprise.notification.consumer;

public class ScenarioRecalculatedNotificationConsumer extends ScenarioRecalculatedConsumer {
    public ScenarioRecalculatedNotificationConsumer(dev.nosurprise.scenario.ScenarioService scenarioService,
                                                  dev.nosurprise.offer.OfferService offerService,
                                                  dev.nosurprise.user.UserService userService,
                                                  dev.nosurprise.notification.NotificationService notificationService,
                                                  org.springframework.context.ApplicationEventPublisher eventPublisher,
                                                  org.springframework.data.redis.core.StringRedisTemplate redis) {
        super(scenarioService, offerService, userService, notificationService, eventPublisher, redis);
    }
}