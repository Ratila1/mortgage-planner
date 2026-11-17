package dev.nosurprise.common.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки Vonage (app.sms.vonage.*), секреты — только из env (VONAGE_API_KEY, VONAGE_API_SECRET).
 * from — имя или номер отправителя: буквенное имя (например, «Vonage APIs») или номер в формате E.164 без «+».
 */
@ConfigurationProperties(prefix = "app.sms.vonage")
public record VonageProperties(String apiKey, String apiSecret, String from) {

    public VonageProperties {
        if (from == null || from.isBlank()) {
            from = "Vonage APIs";
        }
    }

    /** Fail-fast при старте: с provider=vonage без ключей приложение не поднимается, а не падает на первом SMS. */
    void validate() {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("app.sms.provider=vonage requires VONAGE_API_KEY and VONAGE_API_SECRET");
        }
    }
}
