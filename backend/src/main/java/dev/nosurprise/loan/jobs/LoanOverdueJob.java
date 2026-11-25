package dev.nosurprise.loan.jobs;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.loan.LoanProperties;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Просрочка (CREDIT_POLICY.md §5.1). Раз в сутки (по умолчанию 00:10 по Минску):
 * 1) ACTIVE с прошедшей датой возврата → OVERDUE + журнал + уведомление (день 1);
 * 2) напоминания на 3 и 7 день просрочки — строка журнала OVERDUE_REMINDER_SENT {day} + уведомление.
 * Пачки под FOR UPDATE SKIP LOCKED, каждая пачка — своя транзакция. Повторный запуск в тот же день безопасен:
 * переведённые уже не ACTIVE, а напоминание за этот день уже записано в журнал.
 */
@Component
public class LoanOverdueJob {

    /** Статус в событии напоминания: не статус заявки, а маркер для уведомления (как CONTRACT_READY). */
    public static final String REMINDER_STATUS = "OVERDUE_REMINDER";

    private static final Logger log = LoggerFactory.getLogger(LoanOverdueJob.class);
    private static final int BATCH = 100;

    private final LoanApplicationRepository repository;
    private final LoanEventRecorder recorder;
    private final OutboxPublisher outbox;
    private final LoanProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LoanOverdueJob(LoanApplicationRepository repository, LoanEventRecorder recorder, OutboxPublisher outbox,
                          LoanProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.recorder = recorder;
        this.outbox = outbox;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "${app.loan.overdue-cron:0 10 0 * * *}", zone = "${app.loan.business-zone:Europe/Minsk}")
    public void run() {
        markOverdue();
        sendReminders();
    }

    /** Публичный, чтобы тесты могли вызвать синхронно. Возвращает число переведённых в OVERDUE. */
    public int markOverdue() {
        int total = 0;
        while (true) {
            Integer done = tx.execute(status -> {
                Instant now = clock.instant();
                LocalDate today = LocalDate.ofInstant(now, properties.businessZone());
                List<UUID> ids = repository.lockOverdue(today, BATCH);
                for (UUID id : ids) {
                    LoanApplication application = repository.findById(id).orElseThrow();
                    application.markOverdue(now);
                    repository.save(application);
                    recorder.statusChanged(id, LoanStatus.ACTIVE, LoanStatus.OVERDUE, null);
                    publish(application, LoanStatus.OVERDUE.name(), now);
                }
                return ids.size();
            });
            total += done == null ? 0 : done;
            if (done == null || done < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("{} loans became overdue", total);
        }
        return total;
    }

    /** Возвращает число отправленных напоминаний. */
    public int sendReminders() {
        int total = 0;
        for (int day : properties.overdueReminderDays()) {
            while (true) {
                Integer done = tx.execute(status -> {
                    Instant now = clock.instant();
                    LocalDate dueDate = LocalDate.ofInstant(now, properties.businessZone()).minusDays(day);
                    List<UUID> ids = repository.lockOverdueWithoutReminder(dueDate, day, BATCH);
                    for (UUID id : ids) {
                        LoanApplication application = repository.findById(id).orElseThrow();
                        recorder.incident(id, LoanEventType.OVERDUE_REMINDER_SENT, null, Map.of("day", day));
                        publish(application, REMINDER_STATUS, now);
                    }
                    return ids.size();
                });
                total += done == null ? 0 : done;
                if (done == null || done < BATCH) {
                    break;
                }
            }
        }
        if (total > 0) {
            log.info("{} overdue reminders sent", total);
        }
        return total;
    }

    private void publish(LoanApplication application, String status, Instant now) {
        outbox.publish(LoanRoutingKeys.OVERDUE, LoanRoutingKeys.AGGREGATE, application.getId(),
                new LoanApplicationEvent(UUID.randomUUID(), now, application.getId(), application.getUserId(),
                        status));
    }
}
