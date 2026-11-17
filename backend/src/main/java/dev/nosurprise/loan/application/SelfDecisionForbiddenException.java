package dev.nosurprise.loan.application;

/** Админ пытается решить по собственной заявке → 403 SELF_DECISION_FORBIDDEN (§3.4). */
public class SelfDecisionForbiddenException extends RuntimeException {
    public SelfDecisionForbiddenException() {
        super("admin cannot decide on their own application");
    }
}
