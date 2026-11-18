package dev.nosurprise.customer;

/** Телефон профиля не подтверждён кодом → 409 PHONE_NOT_VERIFIED. */
public class PhoneNotVerifiedException extends RuntimeException {
    public PhoneNotVerifiedException() {
        super("phone is not verified");
    }
}
