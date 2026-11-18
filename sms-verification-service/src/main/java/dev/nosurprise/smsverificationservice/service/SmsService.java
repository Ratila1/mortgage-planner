package dev.nosurprise.smsverificationservice.service;

import dev.nosurprise.smsverificationservice.api.error.ConflictException;
import dev.nosurprise.smsverificationservice.api.error.NotFoundException;
import dev.nosurprise.smsverificationservice.api.error.RateLimitException;
import dev.nosurprise.smsverificationservice.config.SmsProperties;
import dev.nosurprise.smsverificationservice.domain.Language;
import dev.nosurprise.smsverificationservice.domain.SmsRequest;
import dev.nosurprise.smsverificationservice.domain.SmsRequestRepository;
import dev.nosurprise.smsverificationservice.domain.SmsStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class SmsService {

    private final SmsRequestRepository repo;
    private final PhoneNormalizer phoneNormalizer;
    private final PhoneLanguageResolver languageResolver;
    private final OtpGenerator otpGenerator;
    private final MessageFormatter messageFormatter;
    private final OtpHasher otpHasher;
    private final RedisOtpStore redisOtpStore;
    private final SmsProperties props;
    private final MeterRegistry metrics;
    private final RedisTemplate<String, Object> redisTemplate;

    public SmsService(SmsRequestRepository repo, PhoneNormalizer phoneNormalizer, PhoneLanguageResolver languageResolver,
                      OtpGenerator otpGenerator, MessageFormatter messageFormatter, OtpHasher otpHasher,
                      RedisOtpStore redisOtpStore, SmsProperties props, MeterRegistry metrics,
                      RedisTemplate<String, Object> redisTemplate) {
        this.repo = repo;
        this.phoneNormalizer = phoneNormalizer;
        this.languageResolver = languageResolver;
        this.otpGenerator = otpGenerator;
        this.messageFormatter = messageFormatter;
        this.otpHasher = otpHasher;
        this.redisOtpStore = redisOtpStore;
        this.props = props;
        this.metrics = metrics;
        this.redisTemplate = redisTemplate;
    }

    public record CreateResult(SmsRequest request, boolean created) {
        public static CreateResult created(SmsRequest request) { return new CreateResult(request, true); }
        public static CreateResult existing(SmsRequest request) { return new CreateResult(request, false); }
    }

    @Transactional
    public CreateResult create(String idempotencyKey, String rawPhone) {
        String phone = phoneNormalizer.toE164(rawPhone);
        checkPhoneRateLimit(phone);

        Optional<dev.nosurprise.smsverificationservice.domain.SmsRequest> existing = repo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getPhone().equals(phone)) {
                throw new ConflictException("Idempotency key used with different phone");
            }
            return CreateResult.existing(existing.get());
        }

        String code = otpGenerator.generate();
        Language lang = languageResolver.resolve(phone);
        String message = messageFormatter.format(lang, code);
        String otpHash = otpHasher.hash(code);

        SmsRequest req = SmsRequest.builder()
            .idempotencyKey(idempotencyKey)
            .phone(phone)
            .language(lang)
            .message(message)
            .status(dev.nosurprise.smsverificationservice.domain.SmsStatus.IN_PROGRESS)
            .attempts(0)
            .maxAttempts(props.maxAttempts())
            .nextAttemptAt(Instant.now())
            .otpHash(otpHash)
            .otpExpiresAt(Instant.now().plus(props.otpTtl()))
            .maxVerifyAttempts(props.maxVerifyAttempts())
            .build();

        SmsRequest saved;
        try {
            saved = repo.save(req);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            return CreateResult.existing(repo.findByIdempotencyKey(idempotencyKey).orElseThrow());
        }

        redisOtpStore.saveOtp(saved.getId(), otpHash, phone);
        metrics.counter("sms_created_total", "language", lang.name()).increment();
        return CreateResult.created(saved);
    }

    public Optional<dev.nosurprise.smsverificationservice.domain.SmsRequest> get(UUID id) {
        return repo.findById(id);
    }

    private void checkPhoneRateLimit(String phone) {
        String key = "ratelimit:phone:" + phone;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(1));
        }
        if (count > props.rateLimit().phonePerMinute()) {
            throw new RateLimitException("Phone rate limit exceeded");
        }
    }
}