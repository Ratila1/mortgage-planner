package dev.nosurprise.sms.service;

import dev.nosurprise.sms.api.error.NotFoundException;
import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.SmsRequest;
import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.domain.SmsStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class SmsVerifyService {

    private final SmsRequestRepository repo;
    private final OtpChallengeService challenges;
    private final SmsProperties props;
    private final MeterRegistry metrics;

    public SmsVerifyService(SmsRequestRepository repo, OtpChallengeService challenges,
                            SmsProperties props, MeterRegistry metrics) {
        this.repo = repo;
        this.challenges = challenges;
        this.props = props;
        this.metrics = metrics;
    }

    public record VerifyResult(boolean verified, String reason) {
        public static VerifyResult success() { return new VerifyResult(true, null); }
        public static VerifyResult invalid() { return new VerifyResult(false, "INVALID_CODE"); }
        public static VerifyResult expired() { return new VerifyResult(false, "EXPIRED"); }
        public static VerifyResult tooManyAttempts() { return new VerifyResult(false, "TOO_MANY_ATTEMPTS"); }
        public static VerifyResult notReady() { return new VerifyResult(false, "NOT_READY"); }
    }

    @Transactional
    public VerifyResult verify(UUID id, String code) {
        String usedKey = OtpChallengeService.smsUsedKey(id);
        String challengeKey = OtpChallengeService.smsKey(id);
        if (challenges.isUsed(usedKey)) return VerifyResult.success();

        SmsRequest req = repo.findById(id).orElseThrow(NotFoundException::new);
        Instant now = Instant.now();

        // Сожжённый или просроченный код больше не активен: EXPIRED приходит до общего not-ready гейта.
        if (req.getStatus() == SmsStatus.EXPIRED) return VerifyResult.expired();
        // Код живёт по expires_at (тот же TTL, что у ключа в Redis) — статус в БД догоняем здесь же.
        if (req.getExpiresAt() != null && !req.getExpiresAt().isAfter(now)) {
            repo.markStatus(id, SmsStatus.EXPIRED, now);
            return VerifyResult.expired();
        }
        // Код выдаётся только после подтверждённой доставки (status = DELIVERED).
        if (req.getStatus() != SmsStatus.DELIVERED) return VerifyResult.notReady();

        return switch (challenges.check(challengeKey, usedKey, code, props.maxVerifyAttempts())) {
            case MISSING -> {
                repo.markStatus(id, SmsStatus.EXPIRED, now);
                yield VerifyResult.expired();
            }
            case TOO_MANY_ATTEMPTS -> {
                repo.markStatus(id, SmsStatus.EXPIRED, now);
                metrics.counter("sms_verify_result_total", "reason", "TOO_MANY_ATTEMPTS").increment();
                yield VerifyResult.tooManyAttempts();
            }
            case INVALID -> {
                metrics.counter("sms_verify_result_total", "reason", "INVALID_CODE").increment();
                yield VerifyResult.invalid();
            }
            case VERIFIED -> {
                repo.markStatus(id, SmsStatus.VERIFIED, now);
                challenges.markUsed(usedKey, OtpChallengeService.USED_TTL);
                metrics.counter("sms_verify_result_total", "reason", "SUCCESS").increment();
                yield VerifyResult.success();
            }
        };
    }
}
