package dev.nosurprise.smsverificationservice.provider;

import dev.nosurprise.smsverificationservice.config.SmsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

@Component
@Profile("sim")
public class SimulatedSmsProvider implements DispatchSmsProvider {

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
    public DispatchSmsProvider.SendResult send(String phone, String text) {
        boolean ok = random.nextDouble() < successRate;
        metrics.counter("sms_provider_result_total", "result", ok ? "success" : "fail").increment();
        return ok
            ? DispatchSmsProvider.SendResult.success("sim-" + UUID.randomUUID())
            : DispatchSmsProvider.SendResult.failure("SIMULATED_DELIVERY_FAILED");
    }
}