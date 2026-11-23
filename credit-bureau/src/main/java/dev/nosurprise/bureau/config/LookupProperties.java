package dev.nosurprise.bureau.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Подключение к базе монолита только на чтение (app.lookup.*, CREDIT_BUREAU.md §5.2): бюро читает из неё
 * один VIEW bureau_applicant_lookup. Пул маленький — монолит и бюро делят один сервер Postgres.
 */
@ConfigurationProperties(prefix = "app.lookup")
public record LookupProperties(String url, String username, String password, int maximumPoolSize,
                               Duration connectionTimeout) {

    public LookupProperties {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("app.lookup.url (LOOKUP_DB_URL) is required");
        }
        if (maximumPoolSize <= 0) {
            maximumPoolSize = 5;
        }
        if (connectionTimeout == null) {
            connectionTimeout = Duration.ofSeconds(3);
        }
    }
}
