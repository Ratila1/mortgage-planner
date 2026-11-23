package dev.nosurprise.sms.provider;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerSmsSenderTest {

    @Test
    @DisplayName("последовательные неуспехи открывают предохранитель и коротко замыкают отправку")
    void opensOnFailures() {
        SmsSender failing = (phone, text) -> SmsSender.SendResult.failure();
        CircuitBreakerSmsSender sender = new CircuitBreakerSmsSender(failing, new SimpleMeterRegistry());

        for (int i = 0; i < 100; i++) {
            SmsSender.SendResult result = sender.send("+375291234567", "Verification code: 123456");
            assertThat(result.ok()).isFalse();
        }

        SmsSender.SendResult shortCircuited = sender.send("+375291234567", "Verification code: 123456");
        assertThat(shortCircuited.ok()).isFalse();
    }
}
