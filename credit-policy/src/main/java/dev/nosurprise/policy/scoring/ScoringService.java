package dev.nosurprise.policy.scoring;

import dev.nosurprise.policy.blacklist.BlacklistService;
import dev.nosurprise.policy.common.ApiException;
import dev.nosurprise.policy.common.ErrorCode;
import dev.nosurprise.policy.config.PolicyProperties;
import dev.nosurprise.policy.rules.ActiveRules;
import dev.nosurprise.policy.rules.RuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Обработка запроса скоринга (CREDIT_POLICY.md §7.2). Идемпотентно по requestId: повторная доставка
 * возвращает уже сохранённый результат, а не считает заново (правила могли смениться между доставками).
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final ScoringResultRepository results;
    private final RuleService rules;
    private final BlacklistService blacklist;
    private final ObjectMapper objectMapper;
    private final PolicyProperties properties;
    private final Clock clock;

    public ScoringService(ScoringResultRepository results, RuleService rules, BlacklistService blacklist, ObjectMapper objectMapper, PolicyProperties properties,
                          Clock clock) {
        this.results = results;
        this.rules = rules;
        this.blacklist = blacklist;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ScoringResult score(ScoringRequested request) {
        Optional<ScoringResult> existing = results.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            log.info("scoring request {} already processed, re-sending the stored result", request.requestId());
            return existing.get();
        }
        ScoringRequested.Applicant applicant = request.applicant() == null
                ? new ScoringRequested.Applicant(null, null, null, null, null, null, null, null)
                : request.applicant();
        ActiveRules active = rules.active();
        // Балл цели — из типа займа в событии (CREDIT_POLICY.md §3.2); справочника целей здесь нет.
        Optional<BigDecimal> purposeScore = request.loan() == null ? Optional.empty()
                : Optional.ofNullable(request.loan().purposeScore());
        boolean blacklisted = blacklist.isBlacklisted(applicant.phone(), applicant.lastName(), applicant.firstName(),
                applicant.middleName(), applicant.birthDate());
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, properties.businessZone());

        String purposeLabel = request.loan() == null ? null : request.loan().loanTypeName();
        ScoringOutcome outcome = ScoringEngine.evaluate(active.rules(), applicant, request.history(), purposeLabel,
                purposeScore, blacklisted, today);
        ScoringResult result = new ScoringResult(request.requestId(), request.applicationId(), request.userId(),
                outcome, objectMapper.writeValueAsString(outcome.reasons()),
                objectMapper.writeValueAsString(outcome.breakdown()), objectMapper.writeValueAsString(request),
                active.version(), now);
        results.save(result);
        log.info("application {} scored: {} {} points={} decision={} rules=v{}", request.applicationId(),
                outcome.status(), outcome.ratingClass(), outcome.points(), outcome.decision(), active.version());
        return result;
    }

    @Transactional(readOnly = true)
    public ScoringResult latest(UUID applicationId) {
        return results.findFirstByApplicationIdOrderByCreatedAtDesc(applicationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.SCORING_NOT_FOUND,
                        "no scoring for this application"));
    }

    @Transactional(readOnly = true)
    public List<ScoringResult> history(UUID applicationId) {
        return results.findByApplicationIdOrderByCreatedAtDesc(applicationId);
    }

    public ScoringCompleted toMessage(ScoringResult result) {
        return new ScoringCompleted(result.getId(), clock.instant(), result.getRequestId(), result.getApplicationId(),
                result.getStatus().name(), result.getScore(), result.getPoints(), result.getRatingClass(),
                result.getPd(), result.getDecision().name(), reasons(result), result.getRulesVersion());
    }

    public List<String> reasons(ScoringResult result) {
        return objectMapper.readValue(result.getReasons(), new TypeReference<List<String>>() {
        });
    }

    public List<FactorScore> breakdown(ScoringResult result) {
        return objectMapper.readValue(result.getBreakdown(), new TypeReference<List<FactorScore>>() {
        });
    }

    public ScoringRequested input(ScoringResult result) {
        return objectMapper.readValue(result.getInput(), ScoringRequested.class);
    }
}
