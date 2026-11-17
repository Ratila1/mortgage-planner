package dev.nosurprise.loan.type;

/** Тип выключен админом и не принимает новые заявки → 409 LOAN_TYPE_INACTIVE. */
public class LoanTypeInactiveException extends RuntimeException {
    public LoanTypeInactiveException() {
        super("loan type is inactive");
    }
}
