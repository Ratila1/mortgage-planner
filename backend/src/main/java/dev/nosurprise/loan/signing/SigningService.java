package dev.nosurprise.loan.signing;

import dev.nosurprise.common.otp.CodeInvalidException;
import dev.nosurprise.common.otp.OneTimeCodeStore;
import dev.nosurprise.common.otp.OneTimeCodeStore.CodeCheck;
import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.common.sms.SmsDeliveryException;
import dev.nosurprise.common.sms.SmsSender;
import dev.nosurprise.customer.CustomerService;
import dev.nosurprise.customer.PhoneNotVerifiedException;
import dev.nosurprise.customer.ProfileView;
import dev.nosurprise.loan.LoanProperties;
import dev.nosurprise.loan.application.InvalidStatusTransitionException;
import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationEvent;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanApplicationService;
import dev.nosurprise.loan.application.LoanEventRecorder;
import dev.nosurprise.loan.application.LoanEventType;
import dev.nosurprise.loan.application.LoanIncidentRecorder;
import dev.nosurprise.loan.application.LoanRoutingKeys;
import dev.nosurprise.loan.application.LoanStatus;
import dev.nosurprise.loan.application.OfferExpiredException;
import dev.nosurprise.loan.contract.DocumentNotFoundException;
import dev.nosurprise.loan.contract.LoanDocument;
import dev.nosurprise.loan.contract.LoanDocumentRepository;
import dev.nosurprise.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Подпись договора кодом из SMS (MICROLOANS.md §3.10). Код привязан к заявке и SHA-256 договора:
 * если договор поменялся после отправки кода, код недействителен.
 * Методы сервиса не держат транзакцию открытой во время SMS и Redis: каждое изменение — короткая транзакция.
 * Проколы (неверный код, подпись без телефона) пишутся в отдельной транзакции: клиенту уходит ошибка,
 * а прокол остаётся в журнале.
 */
@Service
public class SigningService {

    static final String CODE_KEY_PREFIX = "sign-otp:";
    private static final String BOUND_SHA = "documentSha256";
    private static final String BOUND_PHONE = "phone";

    private final LoanApplicationService applications;
    private final LoanApplicationRepository repository;
    private final LoanDocumentRepository documents;
    private final LoanSignatureRepository signatures;
    private final CustomerService customers;
    private final UserService users;
    private final OneTimeCodeStore codes;
    private final SmsSender sms;
    private final LoanEventRecorder recorder;
    private final LoanIncidentRecorder incidents;
    private final OutboxPublisher outbox;
    private final TransactionTemplate tx;
    private final LoanProperties properties;
    private final Clock clock;

    public SigningService(LoanApplicationService applications, LoanApplicationRepository repository,
                          LoanDocumentRepository documents, LoanSignatureRepository signatures,
                          CustomerService customers, UserService users, OneTimeCodeStore codes, SmsSender sms,
                          LoanEventRecorder recorder, LoanIncidentRecorder incidents, OutboxPublisher outbox,
                          PlatformTransactionManager transactionManager, LoanProperties properties, Clock clock) {
        this.applications = applications;
        this.repository = repository;
        this.documents = documents;
        this.signatures = signatures;
        this.customers = customers;
        this.users = users;
        this.codes = codes;
        this.sms = sms;
        this.recorder = recorder;
        this.incidents = incidents;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
    }

    public void requestCode(UUID userId, UUID applicationId) {
        users.assertNotBlocked(userId);
        LoanApplication application = signable(userId, applicationId);
        LoanDocument contract = contractOf(applicationId);
        ProfileView profile = customers.findProfile(userId).orElse(null);
        if (profile == null || !profile.phoneVerified()) {
            incidents.record(applicationId, LoanEventType.SIGN_BLOCKED_PHONE_NOT_VERIFIED, userId, null);
            throw new PhoneNotVerifiedException();
        }
        String key = CODE_KEY_PREFIX + application.getId();
        String cooldownKey = CODE_KEY_PREFIX + "cooldown:" + userId;
        String code = codes.issue(key, cooldownKey,
                Map.of(BOUND_SHA, contract.getSha256(), BOUND_PHONE, profile.phone()));
        try {
            sms.send(profile.phone(), "Код подписи договора микрозайма: " + code);
        } catch (SmsDeliveryException ex) {
            codes.revoke(key, cooldownKey);
            throw ex;
        }
        incidents.record(applicationId, LoanEventType.SIGN_CODE_SENT, userId, null);
    }

    public LoanApplication confirm(UUID userId, UUID applicationId, String code, String ip, String userAgent) {
        users.assertNotBlocked(userId);
        signable(userId, applicationId);
        String key = CODE_KEY_PREFIX + applicationId;
        CodeCheck check = codes.verify(key, code);
        switch (check.result()) {
            case INVALID -> {
                incidents.record(applicationId, LoanEventType.SIGN_CODE_FAILED, userId, null);
                throw new CodeInvalidException("invalid code");
            }
            case BURNED -> {
                incidents.record(applicationId, LoanEventType.SIGN_CODE_FAILED, userId, null);
                incidents.record(applicationId, LoanEventType.SIGN_CODE_BURNED, userId,
                        Map.of("attempts", OneTimeCodeStore.MAX_ATTEMPTS));
                throw new CodeInvalidException("code burned after too many attempts, request a new one");
            }
            case MISSING -> throw new CodeInvalidException("code expired or not requested");
            default -> {
                // OK — проверки ниже
            }
        }
        LoanDocument contract = contractOf(applicationId);
        if (!contract.getSha256().equals(check.bound(BOUND_SHA).orElse(""))) {
            codes.delete(key);
            throw new CodeInvalidException("contract has changed since the code was sent, request a new code");
        }
        String phone = check.bound(BOUND_PHONE).orElse("");
        ProfileView profile = customers.findProfile(userId).orElse(null);
        if (profile == null || !profile.phoneVerified() || !phone.equals(profile.phone())) {
            throw new PhoneNotVerifiedException();
        }

        LoanApplication signed = tx.execute(status -> {
            Instant now = clock.instant();
            LoanApplication application = repository.findById(applicationId).orElseThrow();
            application.activate(now, properties.businessZone());
            repository.save(application);
            signatures.save(new LoanSignature(applicationId, contract.getId(), contract.getSha256(), phone, ip,
                    userAgent, now));
            recorder.statusChanged(applicationId, LoanStatus.APPROVED, LoanStatus.ACTIVE, userId);
            outbox.publish(LoanRoutingKeys.SIGNED, LoanRoutingKeys.AGGREGATE, applicationId,
                    new LoanApplicationEvent(UUID.randomUUID(), now, applicationId, userId,
                            LoanStatus.ACTIVE.name()));
            return application;
        });
        // Код удаляем после коммита: если транзакция упала, клиент может ввести тот же код ещё раз.
        codes.delete(key);
        return signed;
    }

    private LoanApplication signable(UUID userId, UUID applicationId) {
        LoanApplication application = applications.getOwned(userId, applicationId);
        if (application.getStatus() != LoanStatus.APPROVED) {
            throw new InvalidStatusTransitionException(application.getStatus(), LoanStatus.ACTIVE);
        }
        if (application.isOfferExpired(clock.instant())) {
            throw new OfferExpiredException();
        }
        return application;
    }

    private LoanDocument contractOf(UUID applicationId) {
        return documents.findByApplicationIdAndType(applicationId, LoanDocument.TYPE_CONTRACT)
                .orElseThrow(() -> new DocumentNotFoundException("contract is being prepared, try again shortly"));
    }
}
