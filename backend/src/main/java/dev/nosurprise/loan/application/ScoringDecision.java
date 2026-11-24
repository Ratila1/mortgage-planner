package dev.nosurprise.loan.application;

/** Рекомендация credit-policy: к админу «рекомендовано», к админу «высокий риск», отказ системой. */
public enum ScoringDecision {
    RECOMMEND,
    REVIEW,
    AUTO_REJECT
}
