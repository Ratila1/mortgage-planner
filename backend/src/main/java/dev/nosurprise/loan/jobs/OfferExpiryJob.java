package dev.nosurprise.loan.jobs;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationEvent;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanEventRecorder;
import dev.nosurprise.loan.application.LoanEventType;
import dev.nosurprise.loan.application.LoanRoutingKeys;
import dev.nosurprise.loan.application.LoanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Истечение одобренных, но не подписанных предложений (MICROLOANS.md §3.8). Раз в минуту берёт пачку
 * под FOR UPDATE SKIP LOCKED и для каждой заявки в одной транзакции: EXPIRED + журнал (смена статуса
 * и прокол OFFER_NOT_SIGNED) + события outbox.
 */
@Component
public class OfferExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OfferExpiryJob.class);
    private static final int BATCH = 100;

    private final LoanApplicationRepository repository;
    private final LoanEventRecorder recorder;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public OfferExpiryJob(LoanApplicationRepository repository, LoanEventRecorder recorder, OutboxPublisher outbox,
                          Clock clock) {
        this.repository = repository;
        this.recorder = recorder;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Публичный, чтобы тесты могли вызвать задачу синхронно. Возвращает число истёкших заявок. */
    @Scheduled(fixedDelayString = "${app.loan.expiry-interval:60s}")
    @Transactional
    public int expireOffers() {
        Instant now = clock.instant();
        List<UUID> ids = repository.lockExpiredOffers(now, BATCH);
        for (UUID id : ids) {
            LoanApplication application = repository.findById(id).orElseThrow();
            application.expire(now);
            repository.save(application);
            recorder.statusChanged(id, LoanStatus.APPROVED, LoanStatus.EXPIRED, null);
            recorder.incident(id, LoanEventType.OFFER_NOT_SIGNED, null, null);
            outbox.publish(LoanRoutingKeys.EXPIRED, LoanRoutingKeys.AGGREGATE, id,
                    new LoanApplicationEvent(UUID.randomUUID(), now, id, application.getUserId(),
                            LoanStatus.EXPIRED.name()));
        }
        if (!ids.isEmpty()) {
            log.info("expired {} unsigned loan offers", ids.size());
        }
        return ids.size();
    }
}
