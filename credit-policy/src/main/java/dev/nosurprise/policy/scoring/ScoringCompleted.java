package dev.nosurprise.policy.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Результат скоринга для монолита: routing key loan.scoring.completed (CREDIT_POLICY.md §8.2).
 * Расшифровки по факторам здесь нет — её отдаёт админский API сервиса.
 */
public record ScoringCompleted(UUID eventId, Instant occurredAt, UUID requestId, UUID applicationId, String status,
                               BigDecimal score, Integer points, String ratingClass, BigDecimal pd, String decision,
                               List<String> reasons, int rulesVersion) {

    public static final String ROUTING_KEY = "loan.scoring.completed";
}
