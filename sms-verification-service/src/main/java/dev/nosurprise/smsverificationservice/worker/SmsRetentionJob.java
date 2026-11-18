package dev.nosurprise.smsverificationservice.worker;

import dev.nosurprise.smsverificationservice.config.SmsProperties;
import dev.nosurprise.smsverificationservice.domain.SmsRequestRepository;
import dev.nosurprise.smsverificationservice.domain.SmsAttemptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class SmsRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SmsRetentionJob.class);

    private final SmsRequestRepository requestRepo;
    private final SmsAttemptRepository attemptRepo;
    private final SmsProperties props;
    private final MeterRegistry metrics;

    public SmsRetentionJob(SmsRequestRepository requestRepo, SmsAttemptRepository attemptRepo,
                           SmsProperties props, MeterRegistry metrics) {
        this.requestRepo = requestRepo;
        this.attemptRepo = attemptRepo;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupRequests() {
        Instant cutoff = Instant.now().minus(props.retentionRequest());
        int deleted = requestRepo.deleteOlderThan(cutoff);
        metrics.counter("sms_retention_deleted_total", "table", "sms_request").increment(deleted);
        log.info("retention cleanup sms_request: {} rows", deleted);
    }

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void cleanupAttempts() {
        Instant cutoff = Instant.now().minus(props.retentionAttempt());
        int deleted = attemptRepo.deleteOlderThan(cutoff);
        metrics.counter("sms_retention_deleted_total", "table", "sms_attempt").increment(deleted);
        log.info("retention cleanup sms_attempt: {} rows", deleted);
    }
}