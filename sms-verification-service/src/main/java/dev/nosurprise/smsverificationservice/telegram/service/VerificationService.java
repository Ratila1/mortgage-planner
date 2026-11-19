package dev.nosurprise.smsverificationservice.telegram.service;

import dev.nosurprise.smsverificationservice.service.OtpHasher;
import dev.nosurprise.smsverificationservice.telegram.config.VerificationProperties;
import dev.nosurprise.smsverificationservice.telegram.domain.TelegramBinding;
import dev.nosurprise.smsverificationservice.telegram.domain.VerificationRequest;
import dev.nosurprise.smsverificationservice.telegram.domain.VerificationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Telegram 2FA lifecycle: issue a code, verify it and complete the backend operation. */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String OTP_KEY = "tg:otp:%s";
    private static final String USED_KEY = "tg:otp:used:%s";
    private static final Duration USED_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final VerificationRequestRepository repository;
    private final BindingService bindings;
    private final TelegramBotService telegram;
    private final MainBackendClient backend;
    private final TelegramEventPublisher events;
    private final OtpHasher hasher;
    private final Clock clock;
    private final VerificationProperties props;

    public VerificationService(StringRedisTemplate redis, VerificationRequestRepository repository,
                               BindingService bindings, TelegramBotService telegram, MainBackendClient backend,
                               TelegramEventPublisher events, OtpHasher hasher, Clock clock,
                               VerificationProperties props) {
        this.redis = redis;
        this.repository = repository;
        this.bindings = bindings;
        this.telegram = telegram;
        this.backend = backend;
        this.events = events;
        this.hasher = hasher;
        this.clock = clock;
        this.props = props;
    }

    /** Called by the verification.requested consumer. Safe to replay. */
    @Transactional
    public void handleRequested(UUID verificationId, UUID userId, String operationType,
                                String operationRef, int ttlMinutes) {
        if (repository.existsById(verificationId)) {
            log.info("Verification {} already processed, ignoring duplicate", verificationId);
            return;
        }

        Instant now = clock.instant();
        Duration ttl = ttlMinutes > 0 ? Duration.ofMinutes(ttlMinutes) : props.codeTtl();
        repository.save(new VerificationRequest(verificationId, userId, operationType, operationRef,
                "PENDING", now, now.plus(ttl)));

        Optional<TelegramBinding> binding = bindings.findByUser(userId);
        if (binding.isEmpty()) {
            log.warn("No Telegram binding for user {}, verification {} stays PENDING", userId, verificationId);
            return;
        }

        // Write the OTP only when a chat exists: status() derives SENT from the persisted row,
        // and a stray Redis key would otherwise make the client wait for a code never sent.
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String key = OTP_KEY.formatted(verificationId);
        redis.opsForHash().putAll(key, Map.of(
                "hash", hasher.hash(code),
                "userId", userId.toString(),
                "operationRef", operationRef,
                "attempts", "0"));
        redis.expire(key, ttl);

        // Fail loudly so RabbitMQ can retry / dead-letter instead of silently losing the code.
        telegram.sendMessage(binding.get().getTelegramId(), "Ваш код подтверждения: " + code);
        repository.findById(verificationId).ifPresent(request -> request.setStatus("SENT"));
        events.verificationSent(verificationId, userId, operationType);
    }

    /** Verifies the code, then asks the backend to execute the pending operation. */
    public boolean confirm(UUID verificationId, UUID userId, String code) {
        if (repository.findByIdAndUserId(verificationId, userId).isEmpty()) {
            log.warn("User {} tried to confirm verification {} owned by another user", userId, verificationId);
            return false;
        }

        String usedKey = USED_KEY.formatted(verificationId);
        if (Boolean.TRUE.equals(redis.hasKey(usedKey))) return true;

        String key = OTP_KEY.formatted(verificationId);
        Map<Object, Object> stored = redis.opsForHash().entries(key);
        if (stored.isEmpty()) {
            changeStatus(verificationId, "EXPIRED", null);
            return false;
        }
        if (!userId.toString().equals(String.valueOf(stored.get("userId")))) {
            log.warn("User {} tried to confirm verification {} owned by another user",
                    userId, stored.get("userId"));
            return false;
        }
        if (!hasher.verify(code, String.valueOf(stored.get("hash")))) {
            Long attempts = redis.opsForHash().increment(key, "attempts", 1);
            if (attempts != null && attempts >= props.maxAttempts()) {
                redis.delete(key);
                changeStatus(verificationId, "REJECTED", null);
            }
            return false;
        }

        // Complete the business operation first: on a transient backend failure
        // the code stays valid and the client can retry.
        backend.completeVerification(verificationId);

        redis.delete(key);
        redis.opsForValue().set(usedKey, "1", USED_TTL);
        changeStatus(verificationId, "APPROVED", clock.instant());
        events.verificationCompleted(verificationId, userId,
                repository.findById(verificationId).map(VerificationRequest::getOperationType).orElse(""));
        return true;
    }

    public String status(UUID verificationId, UUID userId) {
        VerificationRequest request = repository.findByIdAndUserId(verificationId, userId).orElse(null);
        if (request == null) return "PENDING";
        if (Boolean.TRUE.equals(redis.hasKey(USED_KEY.formatted(verificationId)))) return "APPROVED";
        // The persisted status is authoritative: a Redis key alone is not proof of delivery.
        return request.getStatus();
    }

    private void changeStatus(UUID verificationId, String status, Instant completedAt) {
        repository.findById(verificationId).ifPresent(request -> {
            request.setStatus(status);
            if (completedAt != null) request.setCompletedAt(completedAt);
            repository.save(request);
        });
    }
}
