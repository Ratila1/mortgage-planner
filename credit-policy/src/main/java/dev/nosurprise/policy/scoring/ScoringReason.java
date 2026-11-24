package dev.nosurprise.policy.scoring;

/** Причины, по которым балл не считается: стоп-факторы (класс F) и нехватка данных. */
public enum ScoringReason {
    AGE_OUT_OF_RANGE,
    BLACKLISTED,
    CURRENT_OVERDUE,
    MISSING_PROFILE_DATA,
    /** У типа займа нет балла цели (или он вне 0–1). */
    PURPOSE_SCORE_MISSING
}
