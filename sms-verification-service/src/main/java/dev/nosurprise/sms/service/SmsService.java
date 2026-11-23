package dev.nosurprise.sms.service;

import dev.nosurprise.sms.api.dto.SmsResponse;
import dev.nosurprise.sms.api.error.ConflictException;
import dev.nosurprise.sms.api.error.RateLimitException;
import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.Language;
import dev.nosurprise.sms.domain.SmsRequest;
import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.domain.SmsStatus;
import dev.nosurprise.sms.provider.PhoneMask;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Создание OTP-запроса: нормализация номера, идемпотентность по Idempotency-Key, лимит на номер
 * и раскладка кода (хэш — в Redis-челлендж, открытый код — только в короткоживущий handoff).
 *
 * <p>В БД лежит шаблон с маской ({@code Код подтверждения: ******}): сам OTP в sms_request.message
 * не пишется, иначе хеширование в Redis не защищает от чтения кода из дампа БД.
 */
@Service
public class SmsService {

    private static final String MASKED_CODE = "******";
    private static final String RATE_KEY = "sms:rate:%s";
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /**
     * INCR и EXPIRE одним шагом: иначе падение процесса между ними оставляло бы ключ без TTL
     * и номер был бы заблокирован навсегда.
     */
    private static final DefaultRedisScript<Long> RATE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final SmsRequestRepository repo;
    private final StringRedisTemplate redis;
    private final PhoneNormalizer normalizer;
    private final PhoneLanguageResolver languages;
    private final OtpGenerator otpGenerator;
    private final MessageFormatter messageFormatter;
    private final OtpChallengeService challenges;
    private final SmsCodeHandoffStore handoff;
    private final SmsProperties props;

    public SmsService(SmsRequestRepository repo, StringRedisTemplate redis, PhoneNormalizer normalizer,
                      PhoneLanguageResolver languages, OtpGenerator otpGenerator,
                      MessageFormatter messageFormatter, OtpChallengeService challenges,
                      SmsCodeHandoffStore handoff, SmsProperties props) {
        this.repo = repo;
        this.redis = redis;
        this.normalizer = normalizer;
        this.languages = languages;
        this.otpGenerator = otpGenerator;
        this.messageFormatter = messageFormatter;
        this.challenges = challenges;
        this.handoff = handoff;
        this.props = props;
    }

    /** {@code created=false} — повтор с тем же Idempotency-Key, клиенту отдаётся существующая строка. */
    public record CreateResult(boolean created, SmsRequest request) {}

    public CreateResult create(String idempotencyKey, String rawPhone) {
        String phone = normalizer.toE164(rawPhone);

        // Идемпотентность проверяется до лимита: повтор запроса не должен получать 429
        // и не должен тратить квоту на номер.
        Optional<SmsRequest> existing = repo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            SmsRequest request = existing.get();
            if (!request.getPhone().equals(phone)) {
                throw new ConflictException("Idempotency-Key was already used with a different phone number");
            }
            return new CreateResult(false, request);
        }

        checkPhoneRateLimit(phone);

        Language language = languages.resolve(phone);
        String code = otpGenerator.generate();
        Instant expiresAt = Instant.now().plus(props.otpTtl());

        SmsRequest request = SmsRequest.builder()
            .idempotencyKey(idempotencyKey)
            .phone(phone)
            .language(language)
            .message(messageFormatter.format(language, MASKED_CODE))
            .status(SmsStatus.CREATED)
            .attempts(0)
            .expiresAt(expiresAt)
            .build();
        // saveAndFlush: гонка по уникальному ключу должна проявиться здесь, а не на коммите,
        // чтобы контроллер мог вернуть 200 по уже существующей строке.
        SmsRequest saved = repo.saveAndFlush(request);

        challenges.store(OtpChallengeService.smsKey(saved.getId()), code,
            Map.of("phone", phone, "language", language.name()), props.otpTtl());
        handoff.save(saved.getId(), code);

        return new CreateResult(true, saved);
    }

    public Optional<SmsRequest> get(UUID id) {
        return repo.findById(id);
    }

    /** Повтор после гонки: возвращаем строку, только если ключ принадлежит тому же номеру. */
    public Optional<SmsRequest> findDuplicate(String idempotencyKey, String rawPhone) {
        String phone = normalizer.toE164(rawPhone);
        return repo.findByIdempotencyKey(idempotencyKey).filter(request -> request.getPhone().equals(phone));
    }

    public SmsResponse toResponse(SmsRequest request) {
        return new SmsResponse(request.getId(), request.getStatus().name(), request.getAttempts(),
            request.getCreatedAt(), request.getSentAt(), request.getExpiresAt());
    }

    private void checkPhoneRateLimit(String phone) {
        Long current = redis.execute(RATE_SCRIPT, List.of(RATE_KEY.formatted(phone)),
            String.valueOf(RATE_WINDOW.toSeconds()));
        if (current != null && current > props.rateLimit().phonePerMinute()) {
            throw new RateLimitException("Phone rate limit exceeded for " + PhoneMask.mask(phone));
        }
    }
}
