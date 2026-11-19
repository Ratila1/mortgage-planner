package dev.nosurprise.smsverificationservice.worker;

import dev.nosurprise.smsverificationservice.config.SmsProperties;
import dev.nosurprise.smsverificationservice.domain.SmsRequest;
import dev.nosurprise.smsverificationservice.domain.SmsRequestRepository;
import dev.nosurprise.smsverificationservice.domain.SmsAttemptRepository;
import dev.nosurprise.smsverificationservice.provider.DispatchSmsProvider;
import dev.nosurprise.smsverificationservice.provider.DispatchSmsProvider.SendResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SmsDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(SmsDispatchJob.class);
    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    private final SmsRequestRepository repo;
    private final SmsAttemptRepository attemptRepo;
    private final DispatchSmsProvider dispatchProvider;
    private final TransactionTemplate txTemplate;
    private final SmsProperties props;
    private final MeterRegistry metrics;
    private final AtomicInteger batchSize = new AtomicInteger();

    public SmsDispatchJob(SmsRequestRepository repo, SmsAttemptRepository attemptRepo,
                          DispatchSmsProvider dispatchProvider, TransactionTemplate txTemplate,
                          SmsProperties props, MeterRegistry metrics) {
        this.repo = repo;
        this.attemptRepo = attemptRepo;
        this.dispatchProvider = dispatchProvider;
        this.txTemplate = txTemplate;
        this.props = props;
        this.metrics = metrics;
        Gauge.builder("sms_dispatch_batch_size", batchSize, AtomicInteger::get).register(metrics);
    }

    /** Интервал и стартовая задержка вынесены в свойства: тесты отключают фоновую отправку, вызывая {@link #dispatch()} вручную. */
    @Scheduled(fixedDelayString = "${sms.dispatch-interval:PT5S}",
               initialDelayString = "${sms.dispatch-initial-delay:PT0S}")
    public void dispatch() {
        List<UUID> ids = repo.claimBatch(INSTANCE_ID, props.dispatchBatchSize(), props.lockLease().toMillis());
        batchSize.set(ids.size());

        for (UUID id : ids) {
            try {
                processOne(id);
            } catch (Exception e) {
                metrics.counter("sms_dispatch_error_total").increment();
                log.error("dispatch failed id={}", id, e);
            }
        }
    }

    private void processOne(UUID id) {
        SmsRequest req = repo.findById(id).orElseThrow();
        long start = System.nanoTime();
        SendResult result;
        try {
            result = dispatchProvider.send(req.getPhone(), req.getMessage());
        } catch (Exception e) {
            result = SendResult.failure("PROVIDER_EXCEPTION");
        }
        int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);

        final boolean success = result.success();
        final Instant nextAttempt = backoffFor(req.getAttempts());
        final String errorCode = result.errorCode();
        final String providerMsgId = result.providerMessageId();
        final SmsRequest finalReq = req;
        final SendResult finalResult = result;
        final int finalLatency = latencyMs;

        txTemplate.executeWithoutResult(tx -> {
            repo.finishAttempt(id, success, nextAttempt, errorCode, providerMsgId);
            attemptRepo.save(dev.nosurprise.smsverificationservice.domain.SmsAttempt.of(finalReq, finalResult, finalLatency));
        });
    }

    private Instant backoffFor(int attempts) {
        return switch (attempts) {
            case 1 -> Instant.now().plus(props.firstBackoff());
            case 2 -> Instant.now().plus(props.secondBackoff());
            default -> Instant.now().plus(props.secondBackoff());
        };
    }
}