package dev.nosurprise.loan.application;

import dev.nosurprise.common.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Единственный способ записать событие заявки (MICROLOANS.md §7.6). Строка в loan_events и событие
 * loan.history.changed в outbox пишутся вместе, в текущей транзакции: строка журнала без события —
 * ошибка, Mongo не узнала бы об изменении до сверки.
 */
@Component
public class LoanEventRecorder {

    private final LoanEventRepository repository;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LoanEventRecorder(LoanEventRepository repository, OutboxPublisher outbox, ObjectMapper objectMapper,
                             Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(UUID applicationId, LoanStatus from, LoanStatus to, UUID actorId) {
        record(applicationId, LoanEventType.STATUS_CHANGED, from, to, actorId, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void incident(UUID applicationId, LoanEventType type, UUID actorId, Map<String, Object> details) {
        record(applicationId, type, null, null, actorId, details);
    }

    private void record(UUID applicationId, LoanEventType type, LoanStatus from, LoanStatus to, UUID actorId,
                        Map<String, Object> details) {
        String json = details == null || details.isEmpty() ? null : objectMapper.writeValueAsString(details);
        repository.save(new LoanEvent(applicationId, type, from, to, actorId, json, clock.instant()));
        outbox.publish(LoanRoutingKeys.HISTORY_CHANGED, LoanRoutingKeys.AGGREGATE, applicationId,
                new LoanHistoryChanged(UUID.randomUUID(), clock.instant(), applicationId));
    }
}
