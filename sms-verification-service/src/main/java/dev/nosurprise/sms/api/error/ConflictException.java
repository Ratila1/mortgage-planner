package dev.nosurprise.sms.api.error;

public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}