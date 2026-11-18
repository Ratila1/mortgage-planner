package dev.nosurprise.loan.application;

/**
 * Заявка не найдена → 404 LOAN_APPLICATION_NOT_FOUND. Чужая заявка тоже даёт 404, а не 403:
 * так не раскрывается, что заявка с таким id существует (§5).
 */
public class LoanApplicationNotFoundException extends RuntimeException {
    public LoanApplicationNotFoundException() {
        super("loan application not found");
    }
}
