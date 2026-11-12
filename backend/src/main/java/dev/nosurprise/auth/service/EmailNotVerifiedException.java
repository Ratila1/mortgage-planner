package dev.nosurprise.auth.service;

/**
 * Вход до подтверждения почты (§7): 403 EMAIL_NOT_VERIFIED.
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("email is not verified");
    }
}
