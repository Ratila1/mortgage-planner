package dev.nosurprise.smsverificationservice.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.telegram")
public record TelegramProperties(
        String botToken,
        String botUsername,
        String webhookPath,
        String apiBase,
        boolean pollingEnabled,
        String webhookSecretToken
) {}
