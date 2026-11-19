package dev.nosurprise.smsverificationservice.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.sms.vonage")
public record VonageProperties(String apiKey, String apiSecret, String from, Duration timeout) {

    public VonageProperties {
        if (from == null || from.isBlank()) {
            from = "Vonage APIs";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }

    void validate() {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("app.sms.provider=vonage requires VONAGE_API_KEY and VONAGE_API_SECRET");
        }
    }
}