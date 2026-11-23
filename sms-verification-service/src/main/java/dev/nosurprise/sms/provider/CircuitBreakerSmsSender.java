package dev.nosurprise.sms.provider;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Decorator над выбранным транспортом (vonage/twilio/log): предохранитель и превращение исключений
 * транспорта в {@link SendResult#failure}. Именно он внедряется в dispatch-джобу, когда provider не sim.
 */
@Component
@ConditionalOnExpression("'${app.sms.provider:sim}' != 'sim'")
@Primary
public class CircuitBreakerSmsSender implements SmsSender {

    private final SmsSender transport;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerSmsSender(@Qualifier("smsTransport") SmsSender transport, MeterRegistry metrics) {
        this.transport = transport;

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(100)
            .permittedNumberOfCallsInHalfOpenState(3)
            .recordResult(result -> result instanceof SendResult sendResult && !sendResult.ok())
            .build();

        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("sms-provider");
        metrics.gauge("sms_circuit_breaker_state", circuitBreaker, cb -> switch (cb.getState()) {
            case CLOSED -> 0; case OPEN -> 1; case HALF_OPEN -> 2; default -> -1;
        });
    }

    @Override
    public SendResult send(String phone, String text) {
        Supplier<SendResult> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
            try {
                return transport.send(phone, text);
            } catch (Exception e) {
                return SendResult.failure();
            }
        });
        try {
            return decorated.get();
        } catch (CallNotPermittedException open) {
            return SendResult.failure();
        }
    }
}
