package dev.nosurprise.loan.type;

/** Тип займа не найден → 404 LOAN_TYPE_NOT_FOUND. */
public class LoanTypeNotFoundException extends RuntimeException {
    public LoanTypeNotFoundException() {
        super("loan type not found");
    }
}
