package dev.nosurprise.sms.provider;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@ConditionalOnExpression("'${app.sms.provider:sim}' != 'sim' and ${app.sms.circuit-breaker.enabled:true}")
@Primary
public class CircuitBreakerSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerSmsSender.class);

    private final SmsSender transport;
    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry metrics;

    public CircuitBreakerSmsSender(@Qualifier("smsTransport") SmsSender transport, MeterRegistry metrics) {
        this.transport = transport;
        this.metrics = metrics;

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
                // Транспорт обычно логирует сам, но не все (log/sim) — иначе провал виден
                // только как FAIL в sms_attempt.
                log.warn("sms to {} rejected by transport: {}", PhoneMask.mask(phone), e.toString());
                return SendResult.failure();
            }
        });
        try {
            return decorated.get();
        } catch (CallNotPermittedException open) {
            // Без этой строки открытый предохранитель выглядел как «SMS просто не доехала»:
            // ни вызова провайдера, ни записи в логе.
            log.warn("circuit breaker is open, sms to {} not sent", PhoneMask.mask(phone));
            metrics.counter("sms_circuit_breaker_rejected_total").increment();
            return SendResult.failure();
        }
    }
}
