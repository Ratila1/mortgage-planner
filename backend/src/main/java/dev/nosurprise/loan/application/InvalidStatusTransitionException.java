package dev.nosurprise.loan.application;

/** Переход статуса не разрешён таблицей §3.8 → 409 INVALID_STATUS_TRANSITION. */
public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(LoanStatus from, LoanStatus to) {
        super("status transition " + from + " -> " + to + " is not allowed");
    }
}
