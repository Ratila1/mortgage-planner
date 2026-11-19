package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Статус "Погашен" (REPAID). Конечный.
 */
public final class RepaidState implements LoanState {

    public static final RepaidState INSTANCE = new RepaidState();

    private RepaidState() {
    }

    @Override
    public LoanStatus status() {
        return LoanStatus.REPAID;
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
