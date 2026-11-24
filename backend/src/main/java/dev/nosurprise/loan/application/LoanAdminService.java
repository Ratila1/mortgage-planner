package dev.nosurprise.loan.application;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.customer.CustomerService;
import dev.nosurprise.customer.ProfileView;
import dev.nosurprise.customer.kyc.KycContent;
import dev.nosurprise.customer.kyc.KycService;
import dev.nosurprise.loan.ConcurrentModificationException;
import dev.nosurprise.loan.LoanProperties;
import dev.nosurprise.loan.calc.BusinessDays;
import dev.nosurprise.loan.scoring.ScoringRequester;
import dev.nosurprise.loan.type.LoanTypeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Действия админа с заявками (MICROLOANS.md §3.4): список, карточка, одобрение, отказ, отметка «Погашен»,
 * повторный запрос скоринга. Каждое решение передаёт version: два админа не решат одну заявку дважды (§7.7).
 * Одобрить можно только после скоринга (CREDIT_POLICY.md §6.3); отклонить — в любой момент.
 */
@Service
public class LoanAdminService {

    private final LoanApplicationRepository repository;
    private final LoanEventRecorder recorder;
    private final OutboxPublisher outbox;
    private final KycService kyc;
    private final LoanProperties properties;
    private final ScoringRequester scoring;
    private final LoanTypeService loanTypes;
    private final CustomerService customers;
    private final Clock clock;

    public LoanAdminService(LoanApplicationRepository repository, LoanEventRecorder recorder, OutboxPublisher outbox,
                            KycService kyc, LoanProperties properties, ScoringRequester scoring,
                            LoanTypeService loanTypes, CustomerService customers, Clock clock) {
        this.repository = repository;
        this.recorder = recorder;
        this.outbox = outbox;
        this.kyc = kyc;
        this.properties = properties;
        this.scoring = scoring;
        this.loanTypes = loanTypes;
        this.customers = customers;
        this.clock = clock;
    }

    /** Список строится только из loan_applications: данные заявителя берутся из снимка (§4.7). */
    @Transactional(readOnly = true)
    public Page<LoanApplication> list(LoanStatus status, String query, int page, int size) {
        String q = (query == null || query.isBlank())
                ? null
                : "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.search(status, q, pageable);
    }

    @Transactional(readOnly = true)
    public LoanApplication get(UUID applicationId) {
        return repository.findById(applicationId).orElseThrow(LoanApplicationNotFoundException::new);
    }

    @Transactional
    public LoanApplication approve(UUID applicationId, UUID adminId, String comment, long version) {
        LoanApplication application = decidable(applicationId, adminId, version);
        if (!application.isScoredForDecision()) {
            throw new ScoringRequiredException();
        }
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

    /**
     * Ручная отметка «Погашен» (§3.4): платежей в системе нет, админ ставит её, когда клиент рассчитался.
     * Из ACTIVE и OVERDUE. paidAt — фактическая дата оплаты, по умолчанию сегодня (CREDIT_POLICY.md §5.1).
     */
    @Transactional
    public LoanApplication markRepaid(UUID applicationId, UUID adminId, long version, LocalDate paidAt) {
        LoanApplication application = decidable(applicationId, adminId, version);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, properties.businessZone());
        LoanStatus from = application.getStatus();
        application.markRepaid(now, paidAt == null ? today : paidAt, today);
        return save(application, from, adminId, LoanRoutingKeys.REPAID, now);
    }

    /**
     * Запросить скоринг заново (CREDIT_POLICY.md §6.3): ответ не пришёл (сообщение в DLQ) или заявка подана
     * до появления скоринга. Только для SUBMITTED; ответ на прежний запрос будет проигнорирован.
     */
    @Transactional
    public LoanApplication requestScoring(UUID applicationId) {
        LoanApplication application = get(applicationId);
        if (application.getStatus() != LoanStatus.SUBMITTED) {
            throw new InvalidStatusTransitionException(application.getStatus(), LoanStatus.SUBMITTED);
        }
        ProfileView profile = customers.findProfile(application.getUserId())
                .orElseThrow(dev.nosurprise.customer.ProfileIncompleteException::new);
        scoring.request(application, loanTypes.get(application.getLoanTypeId()), profile);
        return repository.save(application);
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
