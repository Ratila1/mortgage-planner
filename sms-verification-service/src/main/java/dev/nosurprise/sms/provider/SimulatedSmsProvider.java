package dev.nosurprise.sms.provider;

import dev.nosurprise.sms.config.SmsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "sim", matchIfMissing = true)
public class SimulatedSmsProvider implements SmsSender {

    private final Random random;
    private final double successRate;
    private final MeterRegistry metrics;

    public SimulatedSmsProvider(SmsProperties props,
                                @Qualifier("providerRandom") Random random,
                                MeterRegistry metrics) {
        this.random = random;
        this.successRate = props.simulatedSuccessRate();
        this.metrics = metrics;
    }

    @Override
    public SendResult send(String phone, String text) {
        boolean ok = random.nextDouble() < successRate;
        metrics.counter("sms_provider_result_total", "result", ok ? "success" : "fail").increment();
        return ok
            ? SendResult.success()
            : SendResult.failure();
    }
}
