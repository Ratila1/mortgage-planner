package dev.nosurprise.sms.telegram.service;

import dev.nosurprise.sms.api.error.NotFoundException;
import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.SmsRequest;
import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.domain.SmsStatus;
import dev.nosurprise.sms.service.OtpChallengeService;
import dev.nosurprise.sms.service.SmsCodeHandoffStore;
import dev.nosurprise.sms.outbox.OutboxEvent;
import dev.nosurprise.sms.outbox.OutboxEventRepository;
import dev.nosurprise.sms.telegram.domain.TelegramBinding;
import dev.nosurprise.sms.telegram.domain.VerificationRequest;
import dev.nosurprise.sms.telegram.domain.VerificationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Telegram 2FA lifecycle: deliver the SMS code, verify it and complete the backend operation. */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    private static final String DELIVERED_KEY = "tg:otp:delivered:%s";
    private static final Duration DELIVERED_TTL = Duration.ofHours(24);

    public enum DeliveryOutcome { DELIVERED, NO_OP, RETRYABLE, PERMANENT }

    private final StringRedisTemplate redis;
    private final VerificationRequestRepository repository;
    private final BindingService bindings;
    private final TelegramBotService telegram;
    private final MainBackendClient backend;
    private final TelegramEventPublisher events;
    private final OtpChallengeService challenges;
    private final SmsCodeHandoffStore smsCodes;
    private final SmsRequestRepository smsRequests;
    private final OutboxEventRepository outbox;
    private final Clock clock;
    private final SmsProperties smsProps;

    public VerificationService(StringRedisTemplate redis, VerificationRequestRepository repository,
                               BindingService bindings, TelegramBotService telegram, MainBackendClient backend,
                               TelegramEventPublisher events, OtpChallengeService challenges,
                               SmsCodeHandoffStore smsCodes, SmsRequestRepository smsRequests,
                               OutboxEventRepository outbox, Clock clock, SmsProperties smsProps) {
        this.redis = redis;
        this.repository = repository;
        this.bindings = bindings;
        this.telegram = telegram;
        this.backend = backend;
        this.events = events;
        this.challenges = challenges;
        this.smsCodes = smsCodes;
        this.smsRequests = smsRequests;
        this.outbox = outbox;
        this.clock = clock;
        this.smsProps = smsProps;
    }

    /**
     * Вызывается consumer'ом verification.requested. Регистрирует верификацию и, если SMS уже доставлена,
     * ставит идемпотентное outbox-событие для Telegram-доставки. Внешний Telegram-вызов делает relay.
     */
    @Transactional
    public void handleRequested(UUID verificationId, UUID userId, String operationType,
                                String operationRef, UUID smsId, int ttlMinutes) {
        if (repository.existsById(verificationId)) {
            replayPendingDelivery(verificationId);
            return;
        }

        Instant now = clock.instant();
        // TTL кода один на оба канала: событие может попросить меньше, но не больше, чем живёт OTP.
        Duration requested = Duration.ofMinutes(ttlMinutes);
        Duration effectiveTtl = requested.compareTo(smsProps.otpTtl()) <= 0 ? requested : smsProps.otpTtl();
        repository.save(new VerificationRequest(verificationId, userId, operationType, operationRef,
                SmsStatus.CREATED, now, now.plus(effectiveTtl), smsId));

        // Events published before the shared-code contract have no smsId: there is no code to reuse.
        if (smsId == null) {
            log.warn("Verification {} has no smsId, no Telegram code will be sent", verificationId);
            return;
        }

        Optional<SmsRequest> sms = smsRequests.findById(smsId);
        if (sms.isEmpty()) {
            log.warn("SMS request {} for verification {} not found, no Telegram code will be sent",
                    smsId, verificationId);
            return;
        }

        // The dispatch job may have won the race and delivered the SMS before this event was consumed.
        if (sms.get().getStatus() == SmsStatus.DELIVERED) {
            enqueueTelegramDelivery(smsId, now);
            return;
        }
        log.info("SMS {} is {}, Telegram code for verification {} will be sent on successful delivery",
                smsId, sms.get().getStatus(), verificationId);
    }

    /**
     * Повторная доставка события — это retry брокера после сбоя (например, Telegram недоступен).
     * Верификация при этом осталась {@code CREATED}, и именно её нужно дотянуть: считаем событие
     * дублем только если Telegram уже доставлен/завершён, иначе ставим outbox повторно (идемпотентно).
     */
    private void replayPendingDelivery(UUID verificationId) {
        VerificationRequest request = repository.findById(verificationId).orElse(null);
        if (request == null) {
            log.warn("Verification {} is reported as existing but not found, nothing to replay", verificationId);
            return;
        }
        if (request.getStatus() != SmsStatus.CREATED || request.getSmsId() == null) {
            log.info("Verification {} is already {}, ignoring duplicate", verificationId, request.getStatus());
            return;
        }
        log.info("Verification {} is still CREATED, replaying Telegram delivery for SMS {}",
                verificationId, request.getSmsId());
        enqueueTelegramDelivery(request.getSmsId(), clock.instant());
    }

    private void enqueueTelegramDelivery(UUID smsId, Instant now) {
        outbox.insertIfPendingVerification(UUID.randomUUID(), OutboxEvent.SMS_DELIVERED, smsId, now);
    }

    /**
     * Sends the SMS code to Telegram after the SMS provider has confirmed delivery. Called by the outbox
     * relay. Delivers at most once per verification and returns an explicit outcome for retry decisions.
     */
    public DeliveryOutcome deliverBySmsId(UUID smsId) {
        VerificationRequest request = repository.findBySmsIdAndStatus(smsId, SmsStatus.CREATED).orElse(null);
        if (request == null) {
            log.debug("No pending Telegram verification for delivered SMS {}", smsId);
            return DeliveryOutcome.NO_OP;
        }

        UUID verificationId = request.getId();
        Optional<SmsRequest> sms = smsRequests.findById(smsId);
        if (sms.isEmpty() || sms.get().getStatus() != SmsStatus.DELIVERED) {
            log.warn("SMS {} is not delivered, verification {} stays CREATED", smsId, verificationId);
            return DeliveryOutcome.NO_OP;
        }
        if (sms.get().getSentAt() == null) {
            log.warn("SMS {} delivered without sent_at marker, verification {} stays CREATED", smsId, verificationId);
            return DeliveryOutcome.NO_OP;
        }
        if (request.getExpiresAt() == null || !request.getExpiresAt().isAfter(clock.instant())) {
            log.warn("Verification {} expired before Telegram delivery", verificationId);
            return DeliveryOutcome.PERMANENT;
        }

        Optional<TelegramBinding> binding = bindings.findByUser(request.getUserId());
        if (binding.isEmpty()) {
            log.warn("No Telegram binding for user {}, verification {} stays CREATED",
                    request.getUserId(), verificationId);
            return DeliveryOutcome.RETRYABLE;
        }

        // A missing/expired code must not become a second, independently generated OTP.
        Optional<String> code = smsCodes.get(smsId);
        if (code.isEmpty()) {
            log.warn("SMS code for smsId {} is missing or expired, verification {} stays CREATED",
                    smsId, verificationId);
            return DeliveryOutcome.PERMANENT;
        }

        Duration ttl = Duration.between(clock.instant(), request.getExpiresAt());
        if (ttl.isZero() || ttl.isNegative()) {
            log.warn("Verification {} expired before Telegram delivery", verificationId);
            return DeliveryOutcome.PERMANENT;
        }

        // Claim ставится до записи кода: повторный вызов не должен сбрасывать счётчик попыток.
        String deliveredKey = DELIVERED_KEY.formatted(verificationId);
        Boolean claimed = redis.opsForValue().setIfAbsent(deliveredKey, "1", DELIVERED_TTL);
        if (!Boolean.TRUE.equals(claimed)) {
            return reconcileClaimedDelivery(request, verificationId, smsId);
        }

        challenges.store(OtpChallengeService.telegramKey(verificationId), code.get(),
                Map.of("userId", request.getUserId().toString(), "operationRef", request.getOperationRef()), ttl);

        try {
            telegram.sendMessage(binding.get().getTelegramId(), "Ваш код подтверждения: " + code.get());
        } catch (RuntimeException ex) {
            // При ошибке отправки бронь снимается, чтобы retry события мог доставить код повторно.
            redis.delete(deliveredKey);
            log.warn("Telegram send failed for verification {}", verificationId, ex);
            return DeliveryOutcome.RETRYABLE;
        }

        markTelegramDelivered(request, verificationId);
        return DeliveryOutcome.DELIVERED;
    }

    private DeliveryOutcome reconcileClaimedDelivery(VerificationRequest request, UUID verificationId, UUID smsId) {
        VerificationRequest current = repository.findById(verificationId).orElse(request);
        if (current.getStatus() != SmsStatus.CREATED) {
            return DeliveryOutcome.NO_OP;
        }
        // Claim exists but request is still CREATED: crash may have happened after Telegram send but before
        // DB status/event persistence. If the challenge key exists, the send path had progressed far enough;
        // reconcile DB/event without re-sending. If not, keep retryable so outbox is not acked as processed.
        boolean challengeExists = challenges.attributes(OtpChallengeService.telegramKey(verificationId)).isPresent();
        if (!challengeExists) {
            log.warn("Telegram delivery claim exists but challenge/status incomplete for verification {}, sms {}; retrying",
                    verificationId, smsId);
            return DeliveryOutcome.RETRYABLE;
        }
        log.warn("Reconciling Telegram delivery state for verification {} after claim/send interruption", verificationId);
        markTelegramDelivered(current, verificationId);
        return DeliveryOutcome.DELIVERED;
    }

    private void markTelegramDelivered(VerificationRequest request, UUID verificationId) {
        request.setStatus(SmsStatus.DELIVERED);
        repository.save(request);
        events.verificationSent(verificationId, request.getUserId(), request.getOperationType());
    }

    /**
     * Marks the pending Telegram verification as {@link SmsStatus#NOT_DELIVERED} when the SMS delivery
     * failed permanently: the shared code never reached the user, so no second code will be generated.
     */
    public void markNotDelivered(UUID smsId) {
        repository.findBySmsIdAndStatus(smsId, SmsStatus.CREATED).ifPresent(request -> {
            request.setStatus(SmsStatus.NOT_DELIVERED);
            repository.save(request);
        });
    }

    /** Verifies the code, then asks the backend to execute the pending operation. */
    public boolean confirm(UUID verificationId, UUID userId, String code) {
        if (repository.findByIdAndUserId(verificationId, userId).isEmpty()) {
            log.warn("User {} tried to confirm verification {} owned by another user", userId, verificationId);
            return false;
        }

        String usedKey = OtpChallengeService.telegramUsedKey(verificationId);
        if (challenges.isUsed(usedKey)) return true;

        String otpKey = OtpChallengeService.telegramKey(verificationId);
        Optional<Map<String, String>> attributes = challenges.attributes(otpKey);
        if (attributes.isEmpty()) {
            changeStatus(verificationId, SmsStatus.EXPIRED, null);
            return false;
        }
        if (!userId.toString().equals(attributes.get().get("userId"))) {
            log.warn("User {} tried to confirm verification {} owned by another user",
                    userId, attributes.get().get("userId"));
            return false;
        }

        OtpChallengeService.Outcome outcome = challenges.check(otpKey, usedKey, code, smsProps.maxVerifyAttempts());
        if (outcome == OtpChallengeService.Outcome.MISSING
                || outcome == OtpChallengeService.Outcome.TOO_MANY_ATTEMPTS) {
            changeStatus(verificationId, SmsStatus.EXPIRED, null);
            return false;
        }
        if (outcome != OtpChallengeService.Outcome.VERIFIED) return false;

        // Complete the business operation first: on a transient backend failure
        // the code stays valid and the client can retry.
        backend.completeVerification(verificationId);

        challenges.markUsed(usedKey, OtpChallengeService.USED_TTL);
        changeStatus(verificationId, SmsStatus.VERIFIED, clock.instant());
        events.verificationCompleted(verificationId, userId,
                repository.findById(verificationId).map(VerificationRequest::getOperationType).orElse(""));
        return true;
    }

    /**
     * Статус верификации текущего пользователя. Чужой и несуществующий id неразличимы: оба дают 404,
     * иначе перебором можно было бы выяснить, какие верификации существуют.
     */
    public SmsStatus status(UUID verificationId, UUID userId) {
        VerificationRequest request = repository.findByIdAndUserId(verificationId, userId)
                .orElseThrow(NotFoundException::new);
        if (challenges.isUsed(OtpChallengeService.telegramUsedKey(verificationId))) return SmsStatus.VERIFIED;
        // The persisted status is authoritative: a Redis key alone is not proof of delivery.
        return request.getStatus() == null ? SmsStatus.CREATED : request.getStatus();
    }

    private void changeStatus(UUID verificationId, SmsStatus status, Instant completedAt) {
        repository.findById(verificationId).ifPresent(request -> {
            request.setStatus(status);
            if (completedAt != null) request.setCompletedAt(completedAt);
            repository.save(request);
        });
    }
}
