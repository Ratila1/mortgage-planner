package dev.nosurprise.loan.application;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.customer.kyc.KycContent;
import dev.nosurprise.customer.kyc.KycService;
import dev.nosurprise.loan.ConcurrentModificationException;
import dev.nosurprise.loan.LoanProperties;
import dev.nosurprise.loan.calc.BusinessDays;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Действия админа с заявками (MICROLOANS.md §3.4): список, карточка, одобрение, отказ, отметка «Погашен».
 * Каждое решение передаёт version: два админа не решат одну заявку дважды (§7.7).
 */
@Service
public class LoanAdminService {

    private final LoanApplicationRepository repository;
    private final LoanEventRecorder recorder;
    private final OutboxPublisher outbox;
    private final KycService kyc;
    private final LoanProperties properties;
    private final Clock clock;

    public LoanAdminService(LoanApplicationRepository repository, LoanEventRecorder recorder, OutboxPublisher outbox,
                            KycService kyc, LoanProperties properties, Clock clock) {
        this.repository = repository;
        this.recorder = recorder;
        this.outbox = outbox;
        this.kyc = kyc;
        this.properties = properties;
        this.clock = clock;
    }

    /** Список строится только из loan_applications: данные заявителя берутся из снимка (§4.7). */
    @Transactional(readOnly = true)
    public Page<LoanApplication> list(LoanStatus status, int page, int size) {
        return repository.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public LoanApplication get(UUID applicationId) {
        return repository.findById(applicationId).orElseThrow(LoanApplicationNotFoundException::new);
    }

    @Transactional
    public LoanApplication approve(UUID applicationId, UUID adminId, String comment, long version) {
        LoanApplication application = decidable(applicationId, adminId, version);
        Instant now = clock.instant();
        // Срок подписания — 5 рабочих дней в поясе бизнеса (§3.8).
        Instant expiresAt = BusinessDays.plus(now, properties.offerTtlBusinessDays(), properties.businessZone());
        application.approve(adminId, blankToNull(comment), now, expiresAt);
        return save(application, LoanStatus.SUBMITTED, adminId, LoanRoutingKeys.APPROVED, now);
    }

    @Transactional
    public LoanApplication reject(UUID applicationId, UUID adminId, String reason, long version) {
        LoanApplication application = decidable(applicationId, adminId, version);
        Instant now = clock.instant();
        application.reject(adminId, reason.trim(), now);
        return save(application, LoanStatus.SUBMITTED, adminId, LoanRoutingKeys.REJECTED, now);
    }

    /** Ручная отметка «Погашен» (§3.4): платежей в системе нет, админ ставит её, когда клиент рассчитался. */
    @Transactional
    public LoanApplication markRepaid(UUID applicationId, UUID adminId, long version) {
        LoanApplication application = decidable(applicationId, adminId, version);
        Instant now = clock.instant();
        application.markRepaid(now);
        return save(application, LoanStatus.ACTIVE, adminId, LoanRoutingKeys.REPAID, now);
    }

    @Transactional(readOnly = true)
    public KycContent passport(UUID applicationId, UUID adminId, String ip) {
        LoanApplication application = get(applicationId);
        return kyc.loadForAdmin(application.getApplicant().getKycDocumentId(), adminId, ip);
    }

    private LoanApplication decidable(UUID applicationId, UUID adminId, long version) {
        LoanApplication application = get(applicationId);
        ConcurrentModificationException.check(application.getVersion(), version);
        if (application.getUserId().equals(adminId)) {
            throw new SelfDecisionForbiddenException();
        }
        return application;
    }

    private LoanApplication save(LoanApplication application, LoanStatus from, UUID adminId, String routingKey,
                                 Instant now) {
        repository.save(application);
        recorder.statusChanged(application.getId(), from, application.getStatus(), adminId);
        outbox.publish(routingKey, LoanRoutingKeys.AGGREGATE, application.getId(),
                new LoanApplicationEvent(UUID.randomUUID(), now, application.getId(), application.getUserId(),
                        application.getStatus().name()));
        return application;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
