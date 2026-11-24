package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanStatus;

import java.util.Set;

public final class OverdueState implements LoanState {

    public static final OverdueState INSTANCE = new OverdueState();

    private OverdueState() {
    }

    @Override
    public LoanStatus status() {
        return LoanStatus.OVERDUE;
    }

    @Override
    public Set<LoanStatus> allowedTransitions() {
        return Set.of(LoanStatus.REPAID);
    }

    @Override
    public boolean isActive() {
        return true;
    }
}
