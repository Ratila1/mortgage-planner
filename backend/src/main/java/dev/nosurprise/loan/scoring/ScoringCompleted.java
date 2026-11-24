package dev.nosurprise.loan.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ответ credit-policy: loan.scoring.completed (CREDIT_POLICY.md §8.2). Для класса F score, points и pd = null.
 *  * Расшифровки по факторам здесь нет — админка берёт её из API credit-policy.
 */
public record ScoringCompleted(UUID eventId, Instant occurredAt, UUID requestId, UUID applicationId, String status,
                               BigDecimal score, Integer points, String ratingClass, BigDecimal pd, String decision,
                               List<String> reasons, int rulesVersion) {
}
