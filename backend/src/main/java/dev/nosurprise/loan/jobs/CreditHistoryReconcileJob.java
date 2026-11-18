package dev.nosurprise.loan.jobs;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.loan.LoanProperties;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanHistoryChanged;
import dev.nosurprise.loan.application.LoanRoutingKeys;
import dev.nosurprise.loan.history.CreditHistoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Сверка Postgres и Mongo (MICROLOANS.md §4.9) — страховка, если сообщение ушло в DLQ.
 * Раз в час: заявки, у которых отметка синхронизации отстаёт от журнала, получают новое loan.history.changed.
 * Раз в сутки: число заявок в Postgres против числа документов в Mongo, расхождение → ERROR с id.
 */
@Component
public class CreditHistoryReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(CreditHistoryReconcileJob.class);
    private static final int BATCH = 500;
    private static final int MAX_LOGGED_IDS = 100;

    private final LoanApplicationRepository applications;
    private final CreditHistoryStore store;
    private final OutboxPublisher outbox;
    private final LoanProperties properties;
    private final Clock clock;

    public CreditHistoryReconcileJob(LoanApplicationRepository applications, CreditHistoryStore store,
                                     OutboxPublisher outbox, LoanProperties properties, Clock clock) {
        this.applications = applications;
        this.store = store;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.loan.credit-history-reconcile-cron:0 5 * * * *}")
    @Transactional
    public int republishLagging() {
        Instant now = clock.instant();
        List<UUID> lagging = applications.findCreditHistoryLagging(
                now.minus(properties.creditHistoryReconcileDelay()), BATCH);
        for (UUID id : lagging) {
            outbox.publish(LoanRoutingKeys.HISTORY_CHANGED, LoanRoutingKeys.AGGREGATE, id,
                    new LoanHistoryChanged(UUID.randomUUID(), now, id));
        }
        if (!lagging.isEmpty()) {
            log.warn("credit history reconcile: {} applications lagging in Mongo, events re-published", lagging.size());
        }
        return lagging.size();
    }

    @Scheduled(cron = "${app.loan.credit-history-count-check-cron:0 45 3 * * *}")
    @Transactional(readOnly = true)
    public void compareCounts() {
        long postgres = applications.count();
        long mongo = store.count();
        if (postgres == mongo) {
            return;
        }
        List<UUID> missing = new ArrayList<>();
        for (int page = 0; missing.size() < MAX_LOGGED_IDS; page++) {
            List<UUID> ids = applications.findAllIds(PageRequest.of(page, BATCH));
            if (ids.isEmpty()) {
                break;
            }
            Set<UUID> present = store.existingIds(ids);
            ids.stream().filter(id -> !present.contains(id)).limit(MAX_LOGGED_IDS - missing.size())
                    .forEach(missing::add);
        }
        log.error("credit history count mismatch: postgres={}, mongo={}, missing in mongo (first {}): {}",
                postgres, mongo, MAX_LOGGED_IDS, missing);
    }
}
