package dev.nosurprise.loan.jobs;

import dev.nosurprise.customer.kyc.KycProperties;
import dev.nosurprise.customer.kyc.KycService;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Удаление фото паспорта через 7 дней после отказа, отмены или истечения заявки (MICROLOANS.md §3.11).
 * Живёт в loan, а не в customer: правило зависит от статусов заявок, а customer о займах не знает.
 * Фото, на которое ссылается живая или подписанная заявка, не удаляется.
 */
@Component
public class KycRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(KycRetentionJob.class);

    private final LoanApplicationRepository applications;
    private final KycService kyc;
    private final KycProperties properties;
    private final Clock clock;

    public KycRetentionJob(LoanApplicationRepository applications, KycService kyc, KycProperties properties,
                           Clock clock) {
        this.applications = applications;
        this.kyc = kyc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.kyc.retention-cron:0 15 3 * * *}")
    @Transactional
    public int purgeExpiredPassports() {
        List<UUID> ids = applications.findKycDocumentsPastRetention(clock.instant().minus(properties.retention()));
        int purged = kyc.purgeContent(ids);
        if (purged > 0) {
            log.info("kyc retention: content of {} passport photos removed", purged);
        }
        return purged;
    }
}
