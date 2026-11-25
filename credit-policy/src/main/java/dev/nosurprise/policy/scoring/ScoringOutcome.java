package dev.nosurprise.policy.scoring;

import java.math.BigDecimal;
import java.util.List;

/** Результат {@link ScoringEngine}. Для класса F и NOT_CALCULATED score, points и pd = null. */
public record ScoringOutcome(ScoringStatus status, BigDecimal score, Integer points, String ratingClass,
                             BigDecimal pd, ScoringDecision decision, List<String> reasons,
                             List<FactorScore> breakdown) {

    static ScoringOutcome stop(List<String> reasons) {
        return new ScoringOutcome(ScoringStatus.COMPLETED, null, null, RatingScale.STOP_CLASS, null,
                ScoringDecision.AUTO_REJECT, List.copyOf(reasons), List.of());
    }

    static ScoringOutcome notCalculated(ScoringReason reason) {
        return new ScoringOutcome(ScoringStatus.NOT_CALCULATED, null, null, null, null, ScoringDecision.REVIEW,
                List.of(reason.name()), List.of());
    }
}
