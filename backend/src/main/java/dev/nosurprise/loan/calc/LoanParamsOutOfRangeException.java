package dev.nosurprise.loan.calc;

/** Сумма или срок вне лимитов типа → 400 LOAN_PARAMS_OUT_OF_RANGE. */
public class LoanParamsOutOfRangeException extends RuntimeException {
    public LoanParamsOutOfRangeException(String message) {
        super(message);
    }
}
