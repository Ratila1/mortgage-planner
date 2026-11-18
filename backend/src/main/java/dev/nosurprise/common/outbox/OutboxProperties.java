package dev.nosurprise.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Настройки relay и очистки outbox (app.outbox.* в application.yml). */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(int batchSize, int maxAttempts, Duration retention, Duration processedRetention) {

    public OutboxProperties {
        if (batchSize <= 0) {
            batchSize = 100;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 10;
        }
        if (retention == null) {
            retention = Duration.ofDays(7);
        }
        if (processedRetention == null) {
            processedRetention = Duration.ofDays(30);
        }
    }
}
