package dev.nosurprise.loan.application;

/** Одобрение без результата скоринга (CREDIT_POLICY.md §6.3) → 409 SCORING_REQUIRED. */
public class ScoringRequiredException extends RuntimeException {

    public ScoringRequiredException() {
        super("scoring result is required before approval: wait for it or request scoring again");
    }
}
