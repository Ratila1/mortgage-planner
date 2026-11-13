package dev.nosurprise.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Свойства JWT (§7): секрет только из переменной окружения JWT_SECRET (dev-дефолта нет),
 * TTL access 15 мин, refresh 30 дней. Fail-fast на секрет: приложение не должно стартовать,
 * если секрет отсутствует или короче 32 байт (недостаточно для HS256/256 бит).
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl) {

    public JwtProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set and be at least 32 characters long (256 bits for HS256)");
        }
        if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()) {
            throw new IllegalStateException("app.jwt.access-ttl must be a positive duration");
        }
        if (refreshTtl == null || refreshTtl.isZero() || refreshTtl.isNegative()) {
            throw new IllegalStateException("app.jwt.refresh-ttl must be a positive duration");
        }
    }
}
