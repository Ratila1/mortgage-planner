package dev.nosurprise.sms.worker;

import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.outbox.OutboxEvent;
import dev.nosurprise.sms.outbox.OutboxEventRepository;
import dev.nosurprise.sms.outbox.OutboxStatus;
import dev.nosurprise.sms.telegram.service.VerificationService;
import dev.nosurprise.sms.telegram.service.VerificationService.DeliveryOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);
    private static final String INSTANCE_ID = UUID.randomUUID().toString();
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);

    private final OutboxEventRepository outbox;
    private final VerificationService telegramDelivery;
    private final SmsProperties props;
    private final MeterRegistry metrics;

    public OutboxRelayJob(OutboxEventRepository outbox, VerificationService telegramDelivery,
                          SmsProperties props, MeterRegistry metrics) {
        this.outbox = outbox;
        this.telegramDelivery = telegramDelivery;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${sms.outbox-relay-interval:PT5S}",
               initialDelayString = "${sms.outbox-relay-initial-delay:PT2S}")
    public void relay() {
        reconcile();
        List<UUID> ids = outbox.claimBatch(props.dispatchBatchSize(), props.lockLease().toSeconds(), INSTANCE_ID);
        for (UUID id : ids) {
            try {
                processOne(id);
            } catch (Exception e) {
                metrics.counter("sms_outbox_error_total").increment();
                log.error("outbox relay failed for event {}", id, e);
            }
        }
    }

    private void reconcile() {
        try {
            int created = outbox.reconcileMissingDelivered(OutboxEvent.SMS_DELIVERED, Instant.now());
            if (created > 0) {
                log.info("reconciled {} missing Telegram outbox events", created);
                metrics.counter("sms_outbox_reconcile_total").increment(created);
            }
        } catch (Exception e) {
            log.warn("outbox reconciliation failed", e);
        }
    }

    private void processOne(UUID id) {
        OutboxEvent event = outbox.findById(id).orElse(null);
        if (event == null || event.getStatus() == OutboxStatus.PROCESSED || event.getStatus() == OutboxStatus.DEAD) {
            return;
        }
        try {
            DeliveryOutcome outcome = dispatch(event);
            handleOutcome(id, event.getSmsId(), outcome, null);
        } catch (Exception e) {
            handleOutcome(id, event.getSmsId(), DeliveryOutcome.RETRYABLE, e);
        }
    }

    private DeliveryOutcome dispatch(OutboxEvent event) {
        if (OutboxEvent.SMS_DELIVERED.equals(event.getEventType())) {
            return telegramDelivery.deliverBySmsId(event.getSmsId());
        }
        log.warn("unknown outbox event type {} for event {}", event.getEventType(), event.getId());
        return DeliveryOutcome.PERMANENT;
    }

    private void handleOutcome(UUID eventId, UUID smsId, DeliveryOutcome outcome, Exception error) {
        Instant now = Instant.now();
        switch (outcome) {
            case DELIVERED, NO_OP -> {
                outbox.markProcessed(eventId, now);
                metrics.counter("sms_outbox_processed_total", "result", outcome.name()).increment();
            }
            case RETRYABLE -> {
                String reason = error != null ? error.getClass().getSimpleName() : "RETRYABLE";
                outbox.markRetryOrDead(eventId, MAX_ATTEMPTS, RETRY_BACKOFF.toSeconds(), reason, now);
                metrics.counter("sms_outbox_processed_total", "result", "RETRYABLE").increment();
                log.warn("outbox event {} for sms {} retryable: {}", eventId, smsId, reason);
            }
            case PERMANENT -> {
                String reason = error != null ? error.getClass().getSimpleName() : "PERMANENT";
                outbox.markDead(eventId, reason, now);
                metrics.counter("sms_outbox_processed_total", "result", "PERMANENT").increment();
                log.warn("outbox event {} for sms {} dead: {}", eventId, smsId, reason);
            }
        }
    }
}
