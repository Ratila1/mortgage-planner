package dev.nosurprise.customer;

/** More than one profile shares the phone; an internal lookup must not pick an arbitrary user. */
public class AmbiguousPhoneException extends RuntimeException {

    public AmbiguousPhoneException(String phone) {
        super("Several accounts are registered with phone " + phone);
    }
}
