package dev.nosurprise.smsverificationservice.service;

import dev.nosurprise.smsverificationservice.api.error.NotFoundException;
import dev.nosurprise.smsverificationservice.config.SmsProperties;
import dev.nosurprise.smsverificationservice.domain.SmsRequest;
import dev.nosurprise.smsverificationservice.domain.SmsRequestRepository;
import dev.nosurprise.smsverificationservice.domain.SmsStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class SmsVerifyService {

    private final SmsRequestRepository repo;
    private final RedisOtpStore redisOtpStore;
    private final OtpHasher otpHasher;
    private final SmsProperties props;
    private final MeterRegistry metrics;

    public SmsVerifyService(SmsRequestRepository repo, RedisOtpStore redisOtpStore, OtpHasher otpHasher,
                            SmsProperties props, MeterRegistry metrics) {
        this.repo = repo;
        this.redisOtpStore = redisOtpStore;
        this.otpHasher = otpHasher;
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
        if (redisOtpStore.isUsed(id)) return VerifyResult.success();

        SmsRequest req = repo.findById(id).orElseThrow(NotFoundException::new);

        if (req.getStatus() != SmsStatus.SUCCESS) return VerifyResult.notReady();
        if (req.getVerifiedAt() != null) return VerifyResult.success();
        if (Instant.now().isAfter(req.getOtpExpiresAt())) return VerifyResult.expired();
        if (req.getVerifyAttempts() >= req.getMaxVerifyAttempts())
            return VerifyResult.tooManyAttempts();

        int updated = repo.incrementVerifyAttempts(id, req.getMaxVerifyAttempts());
        if (updated == 0) return VerifyResult.tooManyAttempts();

        String hash = redisOtpStore.getOtp(id)
            .map(RedisOtpStore.OtpData::hash)
            .orElse(req.getOtpHash());

        if (!otpHasher.verify(code, hash)) {
            metrics.counter("sms_verify_result_total", "reason", "INVALID_CODE").increment();
            return VerifyResult.invalid();
        }

        repo.markVerified(id);
        redisOtpStore.markUsed(id);
        metrics.counter("sms_verify_result_total", "reason", "SUCCESS").increment();
        return VerifyResult.success();
    }
}