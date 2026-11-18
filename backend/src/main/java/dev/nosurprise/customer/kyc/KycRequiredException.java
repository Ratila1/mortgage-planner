package dev.nosurprise.customer.kyc;

/** Нет фото паспорта (не загружено или удалено по сроку) → 409 KYC_REQUIRED. */
public class KycRequiredException extends RuntimeException {
    public KycRequiredException() {
        super("passport photo is required");
    }
}
