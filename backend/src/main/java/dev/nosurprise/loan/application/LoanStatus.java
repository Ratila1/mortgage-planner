package dev.nosurprise.loan.application;

import dev.nosurprise.loan.application.state.LoanState;

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
    /** Дата возврата прошла, а погашения нет (CREDIT_POLICY.md §5). Переводит ночная задача. */
    OVERDUE,
    EXPIRED,
    REPAID;

    public boolean canTransitionTo(LoanStatus target) {
        return LoanState.of(this).canTransitionTo(target);
    }

    public boolean isActive() {
        return LoanState.of(this).isActive();
    }

    public boolean isFinal() {
        return LoanState.of(this).isFinal();
    }
}
