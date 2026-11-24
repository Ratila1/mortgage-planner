package dev.nosurprise.sms.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.telegram")
public record TelegramProperties(
        String botToken,
        String botUsername,
        String apiBase,
        boolean pollingEnabled,
        String webhookSecretToken,
        Duration timeout
) {
    public TelegramProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.telegram.org";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(5);
        }
    }
}
