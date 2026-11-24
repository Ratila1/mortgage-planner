package dev.nosurprise.loan.application;

/** Дата оплаты при погашении в будущем или раньше подачи заявки → 400 VALIDATION_FAILED. */
public class InvalidPaidDateException extends RuntimeException {

    public InvalidPaidDateException(String message) {
        super(message);
    }
}
