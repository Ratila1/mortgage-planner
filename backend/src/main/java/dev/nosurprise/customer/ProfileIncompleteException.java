package dev.nosurprise.customer;

/** Профиль не заполнен (нет ФИО, телефона или зарплаты) → 409 PROFILE_INCOMPLETE. */
public class ProfileIncompleteException extends RuntimeException {
    public ProfileIncompleteException() {
        super("customer profile is not filled in");
    }
}
