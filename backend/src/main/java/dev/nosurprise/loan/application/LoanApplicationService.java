package dev.nosurprise.loan.application;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.customer.CustomerService;
import dev.nosurprise.customer.PhoneNotVerifiedException;
import dev.nosurprise.customer.ProfileView;
import dev.nosurprise.customer.kyc.KycMetadata;
import dev.nosurprise.customer.kyc.KycService;
import dev.nosurprise.loan.calc.LoanQuote;
import dev.nosurprise.loan.contract.LoanDocument;
import dev.nosurprise.loan.contract.LoanDocumentRepository;
import dev.nosurprise.loan.scoring.ScoringRequester;
import dev.nosurprise.loan.type.LoanType;
import dev.nosurprise.loan.type.LoanTypeInactiveException;
import dev.nosurprise.loan.type.LoanTypeService;
import dev.nosurprise.user.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Действия клиента с заявкой (MICROLOANS.md §3.3): подача, отзыв, история, карточка.
 * Каждое изменение — одна транзакция: заявка, строка журнала loan_events и события outbox вместе (§4.8).
 */
@Service
public class LoanApplicationService {

    private static final List<LoanStatus> ACTIVE_STATUSES =
            List.of(LoanStatus.SUBMITTED, LoanStatus.APPROVED, LoanStatus.ACTIVE, LoanStatus.OVERDUE);

    private final LoanApplicationRepository repository;
    private final LoanDocumentRepository documents;
    private final LoanTypeService loanTypes;
    private final CustomerService customers;
    private final KycService kyc;
    private final UserService users;
    private final LoanEventRecorder recorder;
    private final OutboxPublisher outbox;
    private final ScoringRequester scoring;
    private final Clock clock;

    public LoanApplicationService(LoanApplicationRepository repository, LoanDocumentRepository documents,
                                  LoanTypeService loanTypes, CustomerService customers, KycService kyc,
                                  UserService users, LoanEventRecorder recorder, OutboxPublisher outbox,
                                  ScoringRequester scoring, Clock clock) {
        this.repository = repository;
        this.documents = documents;
        this.loanTypes = loanTypes;
        this.customers = customers;
        this.kyc = kyc;
        this.users = users;
        this.recorder = recorder;
        this.outbox = outbox;
        this.scoring = scoring;
        this.clock = clock;
    }

    /**
     * Подача (§3.3, шаг 5). Профиль, заявка со снимками, журнал и события — одна транзакция:
     * при любой ошибке не остаётся ни обновлённого профиля без заявки, ни заявки без события.
     */
    @Transactional
    public LoanApplication submit(UUID userId, SubmitCommand command) {
        users.assertNotBlocked(userId);
        LoanType type = loanTypes.get(command.loanTypeId());
        if (!type.isActive()) {
            throw new LoanTypeInactiveException();
        }
        // Сумма считается на сервере: клиенту не доверяем.
        LoanQuote quote = LoanTypeService.quote(type, command.amount(), command.termDays());
        // Проверка в коде — только ради понятной ошибки; гарантию даёт частичный уникальный индекс ниже.
        if (repository.existsByUserIdAndStatusIn(userId, ACTIVE_STATUSES)) {
            throw new ActiveApplicationExistsException();
        }
        ProfileView profile = customers.saveProfile(userId, command.profile());
        // SMS подтверждается в форме до подачи: без него заявка не создаётся. Исключение откатывает
        // и сохранённый профиль, потому что submit — одна транзакция.
        if (!profile.phoneVerified()) {
            throw new PhoneNotVerifiedException();
        }
        KycMetadata passport = kyc.requireUsablePassport(userId);

        Instant now = clock.instant();
        LoanApplication application = LoanApplication.submit(userId, type.getId(), quote, profile, passport.id(),
                profile.phoneVerified(), now);
        // Скоринг запрашивается в той же транзакции: заявки без запроса скоринга не бывает (CREDIT_POLICY.md §6.1).
        scoring.request(application, type, profile);
        try {
            repository.saveAndFlush(application);
        } catch (DataIntegrityViolationException ex) {
            // Параллельный запрос успел первым: сработал uq_loan_applications_one_active (V15).
            throw new ActiveApplicationExistsException();
        }
        recorder.statusChanged(application.getId(), null, LoanStatus.SUBMITTED, userId);
        publish(LoanRoutingKeys.SUBMITTED, application, now);
        return application;
    }

    @Transactional
    public LoanApplication cancel(UUID userId, UUID applicationId) {
        LoanApplication application = getOwned(userId, applicationId);
        LoanStatus from = application.getStatus();
        application.cancel(clock.instant());
        repository.save(application);
        recorder.statusChanged(applicationId, from, LoanStatus.CANCELLED, userId);
        return application;
    }

    /** История клиента из Postgres: свои действия он видит сразу, без задержки копии в Mongo (§3.12). */
    @Transactional(readOnly = true)
    public Page<LoanApplication> listOwned(UUID userId, int page, int size) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }

    /** Чужая заявка — 404, а не 403 (§5). */
    @Transactional(readOnly = true)
    public LoanApplication getOwned(UUID userId, UUID applicationId) {
        return repository.findByIdAndUserId(applicationId, userId)
                .orElseThrow(LoanApplicationNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<LoanDocument> documentsOf(UUID applicationId) {
        return documents.findByApplicationIdOrderByCreatedAt(applicationId);
    }

    @Transactional(readOnly = true)
    public Optional<LoanNotificationView> notificationView(UUID applicationId) {
        return repository.findById(applicationId).map(application -> new LoanNotificationView(
                application.getId(), application.getUserId(), application.getStatus(), application.isSignedAtSubmit(),
                application.getDecisionComment(),
                documents.findByApplicationIdAndType(applicationId, LoanDocument.TYPE_CONTRACT)
                        .map(LoanDocument::getId).orElse(null)));
    }

    private void publish(String routingKey, LoanApplication application, Instant now) {
        outbox.publish(routingKey, LoanRoutingKeys.AGGREGATE, application.getId(),
                new LoanApplicationEvent(UUID.randomUUID(), now, application.getId(), application.getUserId(),
                        application.getStatus().name()));
    }
}
