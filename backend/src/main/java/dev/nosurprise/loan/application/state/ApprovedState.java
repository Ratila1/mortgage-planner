package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanStatus;
import dev.nosurprise.loan.application.OfferExpiredException;

import java.time.Instant;
import java.util.Set;

/**
 * Статус "Одобрено, ждёт подписи" (APPROVED).
 * Допустимые переходы: заём выдан, предложение истекло.
 */
public final class ApprovedState implements LoanState {

    public static final ApprovedState INSTANCE = new ApprovedState();

    private ApprovedState() {
    }

    @Override
    public LoanStatus status() {
        return LoanStatus.APPROVED;
    }

    @Override
    public Set<LoanStatus> allowedTransitions() {
        return Set.of(LoanStatus.ACTIVE, LoanStatus.EXPIRED);
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void validateTransition(LoanApplication context, LoanStatus target, Instant now) {
        if (target == LoanStatus.ACTIVE && context.isOfferExpired(now)) {
            throw new OfferExpiredException();
        }
    }
}
