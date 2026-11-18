package dev.nosurprise.smsclient;

/** SMS-сервис отказал по своему лимиту (HTTP 429) → клиенту отдаём 429 RATE_LIMITED, а не 400/502. */
public class SmsClientRateLimitedException extends SmsClientException {

    public SmsClientRateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}
