package dev.nosurprise.loan.signing;

import dev.nosurprise.common.otp.CodeInvalidException;
import dev.nosurprise.common.otp.OtpCooldown;
import dev.nosurprise.common.outbox.OutboxPublisher;
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
import dev.nosurprise.user.UserService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class SigningService {

    static final String SMS_ID_PREFIX = "sign-sms-id:";
    static final String SMS_SHA_PREFIX = "sign-sms-sha:";
    static final String COOLDOWN_KEY_PREFIX = "sign-otp:cooldown:";
    /** TTL привязки совпадает с TTL OTP в sms-verification-service (5 минут). */
    private static final Duration BINDING_TTL = Duration.ofMinutes(5);

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
    private final TransactionTemplate tx;
    private final Clock clock;
    private final StringRedisTemplate redisTemplate;

    public SigningService(LoanApplicationService applications, LoanApplicationRepository repository,
                          LoanDocumentRepository documents, LoanSignatureRepository signatures,
                          CustomerService customers, UserService users, SmsClient smsClient, OtpCooldown cooldown,
                          LoanEventRecorder recorder, LoanIncidentRecorder incidents, OutboxPublisher outbox,
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
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.redisTemplate = redisTemplate;
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
        
        String cooldownKey = COOLDOWN_KEY_PREFIX + userId;
        cooldown.tryAcquire(cooldownKey);

        String idempotencyKey = "signing:" + applicationId + ":" + UUID.randomUUID();
        try {
            SmsResponse response = smsClient.createSms(idempotencyKey, profile.phone());

            // Код привязан к заявке: запоминаем smsId и SHA-256 договора, отправленного на подпись.
            redisTemplate.opsForValue().set(SMS_ID_PREFIX + applicationId, response.getId().toString(), BINDING_TTL);
            redisTemplate.opsForValue().set(SMS_SHA_PREFIX + applicationId, contract.getSha256(), BINDING_TTL);

            incidents.record(applicationId, LoanEventType.SIGN_CODE_SENT, userId, null);
        } catch (RuntimeException e) {
            // SMS не ушла (или её отверг sms-сервис): пауза не нужна, можно повторить сразу.
            cooldown.release(cooldownKey);
            incidents.record(applicationId, LoanEventType.SIGN_CODE_FAILED, userId, null);
            throw e;
        }
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

        LoanApplication signed = tx.execute(status -> {
            Instant now = clock.instant();
            LoanApplication application = repository.findById(applicationId).orElseThrow();
            application.activate(now);
            repository.save(application);
            signatures.save(new LoanSignature(applicationId, contract.getId(), contract.getSha256(), profile.phone(), ip,
                    userAgent, now));
            recorder.statusChanged(applicationId, LoanStatus.APPROVED, LoanStatus.ACTIVE, userId);
            outbox.publish(LoanRoutingKeys.SIGNED, LoanRoutingKeys.AGGREGATE, applicationId,
                    new LoanApplicationEvent(UUID.randomUUID(), now, applicationId, userId,
                            LoanStatus.ACTIVE.name()));
            return application;
        });

        clearBinding(applicationId);
        return signed;
    }

    private void clearBinding(UUID applicationId) {
        redisTemplate.delete(java.util.List.of(SMS_ID_PREFIX + applicationId, SMS_SHA_PREFIX + applicationId));
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