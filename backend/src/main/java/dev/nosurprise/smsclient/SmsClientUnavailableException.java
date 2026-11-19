package dev.nosurprise.smsclient;

public class SmsClientUnavailableException extends SmsClientException {

    public SmsClientUnavailableException(String message) {
        super(message);
    }

    public SmsClientUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}