package dev.nosurprise.sms.worker;

import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.telegram.domain.VerificationRequestRepository;
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
    private final VerificationRequestRepository verificationRepo;
    private final SmsProperties props;
    private final MeterRegistry metrics;

    public SmsRetentionJob(SmsRequestRepository requestRepo, VerificationRequestRepository verificationRepo,
                           SmsProperties props, MeterRegistry metrics) {
        this.requestRepo = requestRepo;
        this.verificationRepo = verificationRepo;
        this.props = props;
        this.metrics = metrics;
    }

    /**
     * Один срок хранения для обоих каналов: sms_attempt удаляется каскадом вместе с запросом,
     * а verification_requests раньше не чистилась вообще и росла вечно.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupRequests() {
        Instant cutoff = Instant.now().minus(props.retentionRequest());
        int requests = requestRepo.deleteOlderThan(cutoff);
        metrics.counter("sms_retention_deleted_total", "table", "sms_request").increment(requests);
        log.info("retention cleanup sms_request: {} rows", requests);

        int verifications = verificationRepo.deleteOlderThan(cutoff);
        metrics.counter("sms_retention_deleted_total", "table", "verification_requests").increment(verifications);
        log.info("retention cleanup verification_requests: {} rows", verifications);
    }
}
