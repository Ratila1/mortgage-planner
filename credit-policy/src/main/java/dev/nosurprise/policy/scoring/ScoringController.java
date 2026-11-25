package dev.nosurprise.policy.scoring;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Tag(name = "Расчёты")
@RestController
@RequestMapping("/api/admin/scorings")
public class ScoringController {

    private final ScoringService scoring;

    public ScoringController(ScoringService scoring) {
        this.scoring = scoring;
    }

    /** pdPercent — PD в процентах (2 знака) для показа; pd — доля, как в событии. */
    public record ScoringResponse(UUID requestId, UUID applicationId, UUID userId, String status, BigDecimal score,
                                  Integer points, String ratingClass, BigDecimal pd, String pdPercent,
                                  String decision, List<String> reasons, List<FactorScore> breakdown,
                                  ScoringRequested input, int rulesVersion, Instant createdAt) {
    }

    @Operation(summary = "Последний расчёт по заявке: класс, скорбалл, PD, решение и расшифровка по факторам")
    @GetMapping("/{applicationId}")
    public ScoringResponse latest(@PathVariable UUID applicationId) {
        return response(scoring.latest(applicationId));
    }

    @Operation(summary = "Все расчёты по заявке, новые сверху (повторные запросы админа)")
    @GetMapping("/{applicationId}/history")
    public List<ScoringResponse> history(@PathVariable UUID applicationId) {
        return scoring.history(applicationId).stream().map(this::response).toList();
    }

    private ScoringResponse response(ScoringResult r) {
        String pdPercent = r.getPd() == null ? null
                : r.getPd().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString();
        return new ScoringResponse(r.getRequestId(), r.getApplicationId(), r.getUserId(), r.getStatus().name(),
                r.getScore(), r.getPoints(), r.getRatingClass(), r.getPd(), pdPercent, r.getDecision().name(),
                scoring.reasons(r), scoring.breakdown(r), scoring.input(r), r.getRulesVersion(), r.getCreatedAt());
    }
}
