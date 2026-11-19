package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanStatus;

import java.util.Set;

/**
 * Статус "Заём выдан" (ACTIVE).
 * Допустимый переход: погашен.
 */
public final class ActiveState implements LoanState {

    public static final ActiveState INSTANCE = new ActiveState();

    private ActiveState() {
    }

    @Override
    public LoanStatus status() {
        return LoanStatus.ACTIVE;
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
