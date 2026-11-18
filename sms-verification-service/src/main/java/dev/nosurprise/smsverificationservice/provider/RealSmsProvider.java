package dev.nosurprise.smsverificationservice.provider;

import dev.nosurprise.smsverificationservice.config.SmsProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Component
@Profile("prod")
public class RealSmsProvider implements DispatchSmsProvider {

    private final SmsSender smsSender;
    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry metrics;

    public RealSmsProvider(SmsSender smsSender, MeterRegistry metrics) {
        this.smsSender = smsSender;
        this.metrics = metrics;

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(100)
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("sms-provider");
        metrics.gauge("sms_circuit_breaker_state", circuitBreaker, cb -> switch (cb.getState()) {
            case CLOSED -> 0; case OPEN -> 1; case HALF_OPEN -> 2; default -> -1;
        });
    }

    @Override
    public DispatchSmsProvider.SendResult send(String phone, String text) {
        java.util.function.Supplier<DispatchSmsProvider.SendResult> decorated =
            CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
                try {
                    smsSender.send(phone, text);
                    return DispatchSmsProvider.SendResult.success("sent-" + UUID.randomUUID());
                } catch (Exception e) {
                    return DispatchSmsProvider.SendResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            });
        return decorated.get();
    }
}