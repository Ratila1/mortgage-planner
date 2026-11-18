package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanStatus;

import java.util.Set;

/**
 * Статус "На рассмотрении" (SUBMITTED).
 * Допустимые переходы: отменена, одобрена, отклонена.
 */
public final class SubmittedState implements LoanState {

    public static final SubmittedState INSTANCE = new SubmittedState();

    private SubmittedState() {
    }

    @Override
    public LoanStatus status() {
        return LoanStatus.SUBMITTED;
    }

    @Override
    public Set<LoanStatus> allowedTransitions() {
        return Set.of(LoanStatus.CANCELLED, LoanStatus.APPROVED, LoanStatus.REJECTED);
    }

    @Override
    public boolean isActive() {
        return true;
    }
}
