package dev.nosurprise.sms.provider;

import dev.nosurprise.sms.config.SmsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Провайдер SMS выбирается одним свойством {@code app.sms.provider}; профили Spring для этого не нужны.
 */
class SmsConfigProdTest {

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(SmsConfig.class);
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(SmsProperties.class, SmsConfigProdTest::testSmsProperties);
        context.registerBean("providerRandom", Random.class, () -> new Random());
        context.scan("dev.nosurprise.sms.provider");
        context.refresh();
        return context;
    }

    private static SmsProperties testSmsProperties() {
        return new SmsProperties(
                List.of("+375", "+7"),
                3,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                5,
                1.0,
                50,
                Duration.ofSeconds(60),
                Duration.ofDays(30),
                new SmsProperties.RateLimit(5)
        );
    }

    @Test
    @DisplayName("provider=vonage: один транспорт Vonage, обёрнутый предохранителем")
    void vonageIsNotDuplicated() {
        try (var context = context(Map.of(
                "app.sms.provider", "vonage",
                "app.sms.vonage.api-key", "key",
                "app.sms.vonage.api-secret", "secret",
                "app.sms.vonage.from", "Test"))) {

            assertThat(context.getBeansOfType(VonageSmsSender.class)).hasSize(1);
            assertThat(context.getBean(SmsSender.class)).isInstanceOf(CircuitBreakerSmsSender.class);
        }
    }

    @Test
    @DisplayName("provider=log: логирующий транспорт создаётся и обёрнут предохранителем")
    void logProviderIsAvailable() {
        try (var context = context(Map.of("app.sms.provider", "log"))) {

            assertThat(context.getBeansOfType(LoggingSmsSender.class)).hasSize(1);
            assertThat(context.getBeansOfType(VonageSmsSender.class)).isEmpty();
            assertThat(context.getBeansOfType(TwilioSmsSender.class)).isEmpty();
            assertThat(context.getBean(SmsSender.class)).isInstanceOf(CircuitBreakerSmsSender.class);
        }
    }

    @Test
    @DisplayName("provider=sim (по умолчанию): симулятор без circuit breaker транспорта")
    void simIsDefault() {
        try (var context = context(Map.of())) {

            assertThat(context.getBean(SmsSender.class)).isInstanceOf(SimulatedSmsProvider.class);
            assertThat(context.getBeansOfType(CircuitBreakerSmsSender.class)).isEmpty();
        }
    }
}
