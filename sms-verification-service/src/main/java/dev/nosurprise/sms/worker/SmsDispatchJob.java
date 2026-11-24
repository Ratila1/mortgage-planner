package dev.nosurprise.sms.worker;

import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.SmsAttempt;
import dev.nosurprise.sms.domain.SmsAttemptRepository;
import dev.nosurprise.sms.domain.SmsRequest;
import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.domain.SmsStatus;
import dev.nosurprise.sms.outbox.OutboxEvent;
import dev.nosurprise.sms.outbox.OutboxEventRepository;
import dev.nosurprise.sms.provider.SmsSender;
import dev.nosurprise.sms.provider.SmsSender.SendResult;
import dev.nosurprise.sms.service.MessageFormatter;
import dev.nosurprise.sms.service.SmsCodeHandoffStore;
import dev.nosurprise.sms.telegram.service.VerificationService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Отправка сохранённых OTP: polling строк sms_request с lease (см. ADR-001).
 * Текст собирается из шаблона и кода из Redis-handoff — в БД лежит только шаблон с маской.
 */
@Component
public class SmsDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(SmsDispatchJob.class);
    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    /** Провайдер не должен получать шквал из 100 запросов; последовательная пачка тоже не годится. */
    private static final int DISPATCH_CONCURRENCY = 8;

    private final SmsRequestRepository repo;
    private final SmsAttemptRepository attemptRepo;
    private final SmsSender provider;
    private final VerificationService telegramDelivery;
    private final OutboxEventRepository outbox;
    private final SmsCodeHandoffStore codeHandoff;
    private final MessageFormatter messageFormatter;
    private final TransactionTemplate txTemplate;
    private final SmsProperties props;
    private final MeterRegistry metrics;
    private final AtomicInteger batchSize = new AtomicInteger();

    public SmsDispatchJob(SmsRequestRepository repo, SmsAttemptRepository attemptRepo, SmsSender provider,
                          VerificationService telegramDelivery, OutboxEventRepository outbox,
                          SmsCodeHandoffStore codeHandoff, MessageFormatter messageFormatter,
                          TransactionTemplate txTemplate, SmsProperties props, MeterRegistry metrics) {
        this.repo = repo;
        this.attemptRepo = attemptRepo;
        this.provider = provider;
        this.telegramDelivery = telegramDelivery;
        this.outbox = outbox;
        this.codeHandoff = codeHandoff;
        this.messageFormatter = messageFormatter;
        this.txTemplate = txTemplate;
        this.props = props;
        this.metrics = metrics;
        Gauge.builder("sms_dispatch_batch_size", batchSize, AtomicInteger::get).register(metrics);
    }

    /** Интервал вынесен в свойства: тесты отключают фоновую отправку и вызывают {@link #dispatch()} вручную. */
    @Scheduled(fixedDelayString = "${sms.dispatch-interval:PT5S}",
               initialDelayString = "${sms.dispatch-initial-delay:PT0S}")
    public void dispatch() {
        // Код с истёкшим TTL в Redis уже сгорел: отправлять его и принимать по нему verify бессмысленно.
        repo.expireStale(Instant.now());
        // Строки с исчерпанными попытками без finishAttempt иначе висели бы в CREATED навсегда.
        repo.closeExhausted(props.maxAttempts());

        List<UUID> ids = repo.claimBatch(props.maxAttempts(), props.dispatchBatchSize(),
                props.firstBackoff().toSeconds(), props.secondBackoff().toSeconds(),
                props.lockLease().toSeconds(), INSTANCE_ID);
        batchSize.set(ids.size());
        if (ids.isEmpty()) return;

        // Виртуальные потоки с ограничением: поток планировщика не ждёт суммарный таймаут провайдера,
        // но и пачка из 100 запросов не бьёт в шлюз одновременно. Lease каждой строки продлевается
        // непосредственно перед отправкой.
        Semaphore permits = new Semaphore(DISPATCH_CONCURRENCY);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID id : ids) {
                executor.execute(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        dispatchOne(id);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
    }

    private void dispatchOne(UUID id) {
        try {
            // Нулевой результат = строка уже не наша либо закрыта — не отправляем.
            if (repo.renewLease(id, INSTANCE_ID, props.lockLease().toSeconds()) == 0) {
                log.info("skip sms {}: lease lost or request already closed", id);
                return;
            }
            processOne(id);
        } catch (Exception e) {
            metrics.counter("sms_dispatch_error_total").increment();
            log.error("dispatch failed id={}", id, e);
        }
    }

    private void processOne(UUID id) {
        SmsRequest request = repo.findById(id).orElseThrow();
        Optional<String> code = codeHandoff.get(id);

        SendResult result;
        if (code.isEmpty()) {
            log.warn("no OTP code in Redis for sms {}, marking attempt as failed", id);
            result = SendResult.failure();
        } else {
            try {
                result = provider.send(request.getPhone(),
                    messageFormatter.format(request.getLanguage(), code.get()));
            } catch (Exception e) {
                // Без этой строки любая неожиданная ошибка провайдера сжигала попытки молча:
                // в логе оставались только FAIL в sms_attempt и итоговый NOT_DELIVERED.
                log.warn("sms {}: provider call failed: {}", id, e.toString());
                metrics.counter("sms_provider_error_total").increment();
                result = SendResult.failure();
            }
        }

        final SendResult sendResult = result;
        final boolean success = sendResult.ok();
        final int maxAttempts = props.maxAttempts();
        final Instant now = Instant.now();

        txTemplate.executeWithoutResult(tx -> {
            repo.finishAttempt(id, success, maxAttempts);
            attemptRepo.save(SmsAttempt.of(request, sendResult));
            // Событие пишется в той же транзакции, что и смена статуса: иначе доставка SMS
            // состоялась бы без намерения отправить код в Telegram.
            if (success) {
                outbox.insertIfPendingVerification(UUID.randomUUID(), OutboxEvent.SMS_DELIVERED, id, now);
            }
        });

        // Итог решает БД: промежуточная неудача возвращает CREATED и оставляет Telegram-канал в игре,
        // поэтому сжигать верификацию можно только на окончательном NOT_DELIVERED.
        SmsStatus outcome = repo.findStatusById(id).orElse(SmsStatus.CREATED);
        if (outcome == SmsStatus.NOT_DELIVERED) {
            telegramDelivery.markNotDelivered(id);
        }
    }
}
