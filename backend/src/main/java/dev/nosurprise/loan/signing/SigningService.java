package dev.nosurprise.loan.signing;

import dev.nosurprise.common.otp.CodeInvalidException;
import dev.nosurprise.common.otp.OtpCooldown;
import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.common.outbox.ProcessedEvents;
import dev.nosurprise.customer.CustomerService;
import dev.nosurprise.customer.PhoneNotVerifiedException;
import dev.nosurprise.customer.ProfileView;
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
import dev.nosurprise.loan.signing.LoanSignatureRepository;
import dev.nosurprise.smsclient.SmsClient;
import dev.nosurprise.smsclient.SmsClientException;
import dev.nosurprise.smsclient.dto.SmsResponse;
import dev.nosurprise.smsclient.dto.VerifyResponse;
import dev.nosurprise.telegram.TelegramRoutingKeys;
import dev.nosurprise.telegram.TelegramVerification;
import dev.nosurprise.telegram.TelegramVerificationRepository;
import dev.nosurprise.telegram.VerificationRequested;
import dev.nosurprise.user.UserService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class SigningService {

    static final String AUTO_SIGN_CONSUMER = "loan.sign-auto";
    static final String SMS_ID_PREFIX = "sign-sms-id:";
    static final String SMS_SHA_PREFIX = "sign-sms-sha:";
    static final String COOLDOWN_KEY_PREFIX = "sign-otp:cooldown:";
    /** TTL привязки совпадает с TTL OTP в sms-verification-service (5 минут). */
    private static final Duration BINDING_TTL = Duration.ofMinutes(5);
    private static final int TELEGRAM_TTL_MINUTES = 5;

    private final LoanApplicationService applications;
    private final LoanApplicationRepository repository;
    private final LoanDocumentRepository documents;
    private final LoanSignatureRepository signatures;
    private final CustomerService customers;
    private final UserService users;
    private final SmsClient smsClient;
    private final OtpCooldown cooldown;
    private final LoanEventRecorder recorder;
    private final LoanIncidentRecorder incidents;
    private final OutboxPublisher outbox;
    private final ProcessedEvents processed;
    private final TelegramVerificationRepository verifications;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final StringRedisTemplate redisTemplate;

    public SigningService(LoanApplicationService applications, LoanApplicationRepository repository,
                          LoanDocumentRepository documents, LoanSignatureRepository signatures,
                          CustomerService customers, UserService users, SmsClient smsClient, OtpCooldown cooldown,
                          LoanEventRecorder recorder, LoanIncidentRecorder incidents, OutboxPublisher outbox,
                          ProcessedEvents processed, TelegramVerificationRepository verifications,
                          PlatformTransactionManager transactionManager, Clock clock,
                          StringRedisTemplate redisTemplate) {
        this.applications = applications;
        this.repository = repository;
        this.documents = documents;
        this.signatures = signatures;
        this.customers = customers;
        this.users = users;
        this.smsClient = smsClient;
        this.cooldown = cooldown;
        this.recorder = recorder;
        this.incidents = incidents;
        this.outbox = outbox;
        this.processed = processed;
        this.verifications = verifications;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Sends the SMS code as before and additionally starts the parallel Telegram 2FA channel.
     * The returned id is used by the client to poll /api/verify/{id}/status.
     */
    public UUID requestCode(UUID userId, UUID applicationId) {
        users.assertNotBlocked(userId);
        LoanApplication application = signable(userId, applicationId);
        LoanDocument contract = contractOf(applicationId);
        ProfileView profile = customers.findProfile(userId).orElse(null);
        if (profile == null || !profile.phoneVerified()) {
            incidents.record(applicationId, LoanEventType.SIGN_BLOCKED_PHONE_NOT_VERIFIED, userId, null);
            throw new PhoneNotVerifiedException();
        }

        String cooldownKey = COOLDOWN_KEY_PREFIX + userId;
        cooldown.tryAcquire(cooldownKey);

        String idempotencyKey = "signing:" + applicationId + ":" + UUID.randomUUID();
        UUID smsId;
        try {
            SmsResponse response = smsClient.createSms(idempotencyKey, profile.phone());
            smsId = response.getId();

            // Код привязан к заявке: запоминаем smsId и SHA-256 договора, отправленного на подпись.
            redisTemplate.opsForValue().set(SMS_ID_PREFIX + applicationId, smsId.toString(), BINDING_TTL);
            redisTemplate.opsForValue().set(SMS_SHA_PREFIX + applicationId, contract.getSha256(), BINDING_TTL);

            incidents.record(applicationId, LoanEventType.SIGN_CODE_SENT, userId, null);
        } catch (RuntimeException e) {
            // SMS не ушла (или её отверг sms-сервис): пауза не нужна, можно повторить сразу.
            cooldown.release(cooldownKey);
            incidents.record(applicationId, LoanEventType.SIGN_CODE_FAILED, userId, null);
            throw e;
        }

        Instant now = clock.instant();
        UUID verificationId = UUID.randomUUID();
        return tx.execute(status -> {
            verifications.save(new TelegramVerification(verificationId, userId, "SIGNING",
                    applicationId.toString(), now, now.plus(Duration.ofMinutes(TELEGRAM_TTL_MINUTES))));
            outbox.publish(TelegramRoutingKeys.VERIFICATION_REQUESTED, "telegram-verification", verificationId,
                    new VerificationRequested(UUID.randomUUID(), now, verificationId, smsId, userId,
                            "SIGNING", applicationId.toString(), TELEGRAM_TTL_MINUTES));
            return verificationId;
        });
    }

    public LoanApplication confirm(UUID userId, UUID applicationId, String code, String ip, String userAgent) {
        users.assertNotBlocked(userId);
        signable(userId, applicationId);

        String smsIdValue = redisTemplate.opsForValue().get(SMS_ID_PREFIX + applicationId);
        UUID smsId = smsIdValue != null ? UUID.fromString(smsIdValue) : null;

        if (smsId == null) {
            incidents.record(applicationId, LoanEventType.SIGN_CODE_FAILED, userId, null);
            throw new CodeInvalidException("code expired or not requested");
        }

        VerifyResponse response;
        try {
            response = smsClient.verify(smsId, code);
        } catch (SmsClientException e) {
            incidents.record(applicationId, LoanEventType.SIGN_CODE_FAILED, userId, null);
            throw e;
        }

        if (!response.isVerified()) {
            switch (response.getReason()) {
                case "INVALID_CODE" -> {
                    incidents.record(applicationId, LoanEventType.SIGN_CODE_FAILED, userId, null);
                    throw new CodeInvalidException("invalid code");
                }
                case "EXPIRED" -> {
                    clearBinding(applicationId);
                    throw new CodeInvalidException("code expired or not requested");
                }
                case "TOO_MANY_ATTEMPTS" -> {
                    clearBinding(applicationId);
                    incidents.record(applicationId, LoanEventType.SIGN_CODE_BURNED, userId,
                            Map.of("reason", "TOO_MANY_ATTEMPTS"));
                    throw new CodeInvalidException("code burned after too many attempts, request a new one");
                }
                case "NOT_READY" -> throw new CodeInvalidException("SMS not yet sent, please wait");
                default -> throw new CodeInvalidException("invalid code");
            }
        }

        LoanDocument contract = contractOf(applicationId);
        if (!contract.getSha256().equals(redisTemplate.opsForValue().get(SMS_SHA_PREFIX + applicationId))) {
            clearBinding(applicationId);
            throw new CodeInvalidException("contract has changed since the code was sent, request a new code");
        }

        ProfileView profile = customers.findProfile(userId).orElse(null);
        if (profile == null || !profile.phoneVerified()) {
            throw new PhoneNotVerifiedException();
        }

        LoanApplication signed = signApplication(userId, applicationId, contract, profile.phone(), ip, userAgent);
        clearBinding(applicationId);
        return signed;
    }

    /** Executes the operation approved by a Telegram code. Idempotent by verification id. */
    @Transactional
    public void completeTelegramVerification(UUID verificationId) {
        TelegramVerification verification = verifications.findById(verificationId)
                .orElseThrow(() -> new CodeInvalidException("unknown verification"));
        if ("COMPLETED".equals(verification.getStatus())) return;
        if (verification.getExpiresAt().isBefore(clock.instant())) {
            verification.setStatus("EXPIRED");
            verifications.save(verification);
            throw new CodeInvalidException("verification expired");
        }

        UUID userId = verification.getUserId();
        UUID applicationId = UUID.fromString(verification.getOperationRef());
        users.assertNotBlocked(userId);
        signable(userId, applicationId);

        LoanDocument contract = contractOf(applicationId);
        String sentSha = redisTemplate.opsForValue().get(SMS_SHA_PREFIX + applicationId);
        if (sentSha != null && !contract.getSha256().equals(sentSha)) {
            throw new CodeInvalidException("contract has changed since the code was sent, request a new code");
        }

        ProfileView profile = customers.findProfile(userId).orElse(null);
        if (profile == null || !profile.phoneVerified()) {
            throw new PhoneNotVerifiedException();
        }

        // The conditional update is the idempotency guard: a concurrent caller blocks on this
        // row and sees zero affected rows after the winner commits. It shares the transaction
        // with signApplication(), so a failed signing rolls the claim back.
        if (verifications.claimCompletion(verificationId, clock.instant()) == 0) return;
        signApplication(userId, applicationId, contract, profile.phone(), "telegram", "telegram");
    }

    /**
     * Авто-активация заявок, подписанных в форме подачи: договор уже сохранён, SMS повторно не нужна.
     * Идемпотентна по eventId и статусу; истёкшую оферту догоняет OfferExpiryJob.
     */
    @Transactional
    public void autoSignOnContractReady(LoanApplicationEvent event) {
        if (!processed.markProcessed(AUTO_SIGN_CONSUMER, event.eventId())) {
            return;
        }
        LoanApplication application = repository.findById(event.applicationId()).orElse(null);
        if (application == null || !application.isSignedAtSubmit()
                || application.getStatus() != LoanStatus.APPROVED
                || application.isOfferExpired(clock.instant())) {
            return;
        }
        LoanDocument contract = contractOf(event.applicationId());
        signApplication(application.getUserId(), event.applicationId(), contract, application.getApplicant().getPhone(),
                "auto", "auto");
    }

    private LoanApplication signApplication(UUID userId, UUID applicationId, LoanDocument contract,
                                            String phone, String ip, String userAgent) {
        return tx.execute(status -> {
            Instant now = clock.instant();
            LoanApplication application = repository.findById(applicationId).orElseThrow();
            application.activate(now);
            repository.save(application);
            signatures.save(new LoanSignature(applicationId, contract.getId(), contract.getSha256(), phone, ip,
                    userAgent, now));
            recorder.statusChanged(applicationId, LoanStatus.APPROVED, LoanStatus.ACTIVE, userId);
            outbox.publish(LoanRoutingKeys.SIGNED, LoanRoutingKeys.AGGREGATE, applicationId,
                    new LoanApplicationEvent(UUID.randomUUID(), now, applicationId, userId,
                            LoanStatus.ACTIVE.name()));
            return application;
        });
    }

    private void clearBinding(UUID applicationId) {
        redisTemplate.delete(java.util.List.of(SMS_ID_PREFIX + applicationId, SMS_SHA_PREFIX + applicationId));
    }

    private LoanApplication signable(UUID userId, UUID applicationId) {
        LoanApplication application = applications.getOwned(userId, applicationId);
        // Такие заявки подписаны ещё при подаче: ручной код не нужен и не должен менять номер в подписи.
        if (application.isSignedAtSubmit()) {
            throw new InvalidStatusTransitionException(application.getStatus(), LoanStatus.ACTIVE);
        }
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
