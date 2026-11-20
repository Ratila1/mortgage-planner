package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Статус "Отклонена" (REJECTED). Конечный.
 */
public final class RejectedState implements LoanState {

    public static final RejectedState INSTANCE = new RejectedState();

    private RejectedState() {
    }

    @Override
    public LoanStatus status() {
        return LoanStatus.REJECTED;
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
        LoanState.super.onEnter(context, actorId, now);
        context.setClosedAt(now);
    }
}
