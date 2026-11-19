package dev.nosurprise.smsverificationservice.api.error;

public class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("Resource not found");
    }
}