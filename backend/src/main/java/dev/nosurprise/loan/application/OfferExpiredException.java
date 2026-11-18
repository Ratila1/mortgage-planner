package dev.nosurprise.loan.application;

/** Срок подписания одобренного предложения истёк → 409 OFFER_EXPIRED. */
public class OfferExpiredException extends RuntimeException {
    public OfferExpiredException() {
        super("offer has expired");
    }
}
