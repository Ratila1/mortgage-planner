package dev.nosurprise.loan.application;

/**
 * Идентификаторы статусов заявки на микрозайм (MICROLOANS.md §3.8).
 * Логика допустимых переходов вынесена в {@link dev.nosurprise.loan.application.state.LoanState}
 * и конкретные реализации состояний в пакете {@code state}.
 */
public enum LoanStatus {
    SUBMITTED,
    APPROVED,
    REJECTED,
    CANCELLED,
    ACTIVE,
    EXPIRED,
    REPAID;
}
