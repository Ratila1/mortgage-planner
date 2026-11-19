package dev.nosurprise.notificationservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.telegram")
public record TelegramProperties(String botToken, String botUsername, String webhookPath, String apiBase) {}