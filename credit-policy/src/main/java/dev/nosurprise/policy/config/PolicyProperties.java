package dev.nosurprise.policy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * Настройки сервиса. jwtSecret — тот же JWT_SECRET, что у монолита: админка ходит сюда с токеном монолита.
 * businessZone — пояс, в котором считается возраст на дату расчёта.
 */
@ConfigurationProperties(prefix = "app.policy")
public record PolicyProperties(String jwtSecret, ZoneId businessZone) {

    public PolicyProperties {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be set and be at least 32 characters long");
        }
        if (businessZone == null) {
            businessZone = ZoneId.of("Europe/Minsk");
        }
    }
}
