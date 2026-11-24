package dev.nosurprise.sms.api.error;

public class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("Resource not found");
    }
}