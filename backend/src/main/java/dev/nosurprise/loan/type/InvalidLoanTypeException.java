package dev.nosurprise.loan.type;

/** Некорректные параметры типа займа → 400 VALIDATION_FAILED. */
public class InvalidLoanTypeException extends RuntimeException {
    public InvalidLoanTypeException(String message) {
        super(message);
    }
}
