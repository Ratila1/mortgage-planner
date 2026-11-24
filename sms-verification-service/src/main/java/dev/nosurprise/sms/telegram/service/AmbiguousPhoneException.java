package dev.nosurprise.sms.telegram.service;

/** The backend found several accounts for the phone, so the chat must not be bound to a guess. */
public class AmbiguousPhoneException extends RuntimeException {

    public AmbiguousPhoneException() {
        super("Several accounts are registered with this phone number");
    }
}
