package dev.nosurprise.bureau.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки бюро (app.bureau.*, CREDIT_BUREAU.md §8–§9).
 * adminKey — ключ админского API (пусто — админский API закрыт); sandboxKey — ключ песочницы,
 * создаваемой при первом старте; лимиты по умолчанию для новых провайдеров (CREDIT_BUREAU.md §8).
 */
@ConfigurationProperties(prefix = "app.bureau")
public record BureauProperties(
        String adminKey,
        String sandboxKey,
        int defaultRequestsPerMinute,
        int defaultRequestsPerDay,
        int sandboxRequestsPerMinute,
        int sandboxRequestsPerDay,
        int authFailuresPerWindow,
        Duration authFailureWindow,
        Duration idempotencyTtl) {

    public BureauProperties {
        defaultRequestsPerMinute = positive(defaultRequestsPerMinute, 60);
        defaultRequestsPerDay = positive(defaultRequestsPerDay, 5000);
        sandboxRequestsPerMinute = positive(sandboxRequestsPerMinute, 10);
        sandboxRequestsPerDay = positive(sandboxRequestsPerDay, 100);
        authFailuresPerWindow = positive(authFailuresPerWindow, 10);
        if (authFailureWindow == null) {
            authFailureWindow = Duration.ofMinutes(10);
        }
        if (idempotencyTtl == null) {
            idempotencyTtl = Duration.ofHours(24);
        }
    }

    public boolean adminApiEnabled() {
        return adminKey != null && !adminKey.isBlank();
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
