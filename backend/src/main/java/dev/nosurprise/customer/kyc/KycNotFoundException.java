package dev.nosurprise.customer.kyc;

/** Документа нет или его содержимое уже удалено → 404 KYC_NOT_FOUND. */
public class KycNotFoundException extends RuntimeException {
    public KycNotFoundException() {
        super("passport photo not found");
    }
}
