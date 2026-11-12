package dev.nosurprise.scenario;

public class InvalidOfferException extends RuntimeException {
    public InvalidOfferException(String message) {
        super(message);
    }
}