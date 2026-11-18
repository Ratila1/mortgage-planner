package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Статус "Предложение истекло" (EXPIRED). Конечный.
 */
public final class ExpiredState implements LoanState {

    public static final ExpiredState INSTANCE = new ExpiredState();

    private ExpiredState() {
    }

    @Override
    public LoanStatus status() {
        return LoanStatus.EXPIRED;
    }

    @Override
    public Set<LoanStatus> allowedTransitions() {
        return Set.of();
    }

    @Override
    public boolean isFinal() {
        return true;
    }

    @Override
    public void onEnter(LoanApplication context, UUID actorId, Instant now) {
        context.setClosedAt(now);
    }
}
