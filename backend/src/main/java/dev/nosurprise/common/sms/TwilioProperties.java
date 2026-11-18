package dev.nosurprise.common.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки Twilio (app.sms.twilio.*), секреты — только из env.
 * accountSid — SID аккаунта (AC...), входит в URL запроса.
 * apiKeySid — SID API-ключа (SK...); если пусто, логином служит accountSid, а authSecret — это Auth Token.
 * authSecret — секрет API-ключа или Auth Token.
 * from — номер-отправитель Twilio в формате E.164; либо messagingServiceSid (MG...) вместо него.
 */
@ConfigurationProperties(prefix = "app.sms.twilio")
public record TwilioProperties(String baseUrl, String accountSid, String apiKeySid, String authSecret, String from,
                               String messagingServiceSid, Duration timeout) {

    public TwilioProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.twilio.com";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }

    /** Fail-fast при старте: с provider=twilio без ключей приложение не поднимается, а не падает на первом SMS. */
    void validate() {
        if (blank(accountSid) || blank(authSecret)) {
            throw new IllegalStateException("app.sms.provider=twilio requires TWILIO_ACCOUNT_SID and TWILIO_AUTH_SECRET");
        }
        if (blank(from) && blank(messagingServiceSid)) {
            throw new IllegalStateException("app.sms.provider=twilio requires TWILIO_FROM or TWILIO_MESSAGING_SERVICE_SID");
        }
    }

    String username() {
        return blank(apiKeySid) ? accountSid : apiKeySid;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
