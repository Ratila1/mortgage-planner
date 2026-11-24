package dev.nosurprise.loan.web.dto;

import dev.nosurprise.loan.application.LoanApplication;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Итог скоринга для админа (CREDIT_POLICY.md §6.4). status: PENDING | COMPLETED | NOT_CALCULATED;
 * null — заявка подана до появления скоринга. Расшифровка по факторам — в API credit-policy.
 * pdPercent — PD в процентах, 2 знака.
 */
public record ScoringView(String status, String score, Integer points, String ratingClass, String pdPercent,
                          String decision, Integer rulesVersion, Instant requestedAt, Instant completedAt) {

    public static ScoringView from(LoanApplication a) {
        if (a.getScoringStatus() == null) {
            return null;
        }
        return new ScoringView(a.getScoringStatus().name(),
                a.getScore() == null ? null : a.getScore().toPlainString(),
                a.getScorePoints(), a.getRatingClass(),
                a.getPd() == null ? null
                        : a.getPd().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                a.getScoringDecision() == null ? null : a.getScoringDecision().name(),
                a.getRulesVersion(), a.getScoringRequestedAt(), a.getScoringCompletedAt());
    }
}
