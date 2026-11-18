package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Статус "Отменена" (CANCELLED). Конечный.
 */
public final class CancelledState implements LoanState {

    public static final CancelledState INSTANCE = new CancelledState();

    private CancelledState() {
    }

    @Override
    public LoanStatus status() {
        return LoanStatus.CANCELLED;
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
