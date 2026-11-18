package dev.nosurprise.smsverificationservice.api.error;

public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}