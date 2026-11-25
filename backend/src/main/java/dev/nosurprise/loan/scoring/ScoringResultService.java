package dev.nosurprise.loan.scoring;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationEvent;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanEventRecorder;
import dev.nosurprise.loan.application.LoanRoutingKeys;
import dev.nosurprise.loan.application.LoanStatus;
import dev.nosurprise.loan.application.ScoringDecision;
import dev.nosurprise.loan.application.ScoringStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Приём результата скоринга (CREDIT_POLICY.md §6.2). Идемпотентно: устаревший (был повторный запрос) или уже
 * записанный ответ игнорируется. Гонка с действием админа решается @Version заявки: исключение → повтор
 * доставки → на повторе виден новый статус.
 */
@Service
public class ScoringResultService {

    /** Общая причина для клиента: расшифровку факторов не показываем (§3.6). */
    public static final String AUTO_REJECT_REASON =
            "Заявка не прошла автоматическую проверку. Подать новую заявку можно позже.";

    private static final Logger log = LoggerFactory.getLogger(ScoringResultService.class);

    private final LoanApplicationRepository repository;
    private final LoanEventRecorder recorder;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public ScoringResultService(LoanApplicationRepository repository, LoanEventRecorder recorder,
                                OutboxPublisher outbox, Clock clock) {
        this.repository = repository;
        this.recorder = recorder;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void apply(ScoringCompleted result) {
        LoanApplication application = repository.findById(result.applicationId()).orElse(null);
        if (application == null) {
            log.warn("scoring result {} for unknown application {}", result.requestId(), result.applicationId());
            return;
        }
        Instant now = clock.instant();
        ScoringDecision decision = ScoringDecision.valueOf(result.decision());
        boolean applied = application.applyScoring(result.requestId(), ScoringStatus.valueOf(result.status()),
                result.score(), result.points(), result.ratingClass(), result.pd(), decision, result.rulesVersion(),
                now);
        if (!applied) {
            log.info("scoring result {} for application {} is stale or already applied, skipping",
                    result.requestId(), result.applicationId());
            return;
        }
        log.info("application {} scored: {} {} points={} decision={}", application.getId(), result.status(),
                result.ratingClass(), result.points(), decision);
        // Автоотказ — только пока заявка ждёт решения; отменённой или уже решённой — просто записываем итог.
        if (decision == ScoringDecision.AUTO_REJECT && application.getStatus() == LoanStatus.SUBMITTED) {
            application.reject(null, AUTO_REJECT_REASON, now);
            repository.save(application);
            recorder.statusChanged(application.getId(), LoanStatus.SUBMITTED, LoanStatus.REJECTED, null);
            outbox.publish(LoanRoutingKeys.REJECTED, LoanRoutingKeys.AGGREGATE, application.getId(),
                    new LoanApplicationEvent(UUID.randomUUID(), now, application.getId(), application.getUserId(),
                            LoanStatus.REJECTED.name()));
            return;
        }
        repository.save(application);
    }
}
