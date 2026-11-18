package dev.nosurprise.smsverificationservice.api.error;

public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}