package dev.nosurprise.smsverificationservice.service;

import dev.nosurprise.smsverificationservice.config.SmsProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisOtpStore {

    private final RedisTemplate<String, Object> redis;
    private final SmsProperties props;

    private static final String OTP_KEY = "sms:otp:%s";
    private static final String CODE_KEY = "sms:otp:code:%s";
    private static final String USED_KEY = "sms:otp:used:%s";

    public RedisOtpStore(RedisTemplate<String, Object> redis, SmsProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void saveOtp(UUID smsId, String hash, String phone) {
        Map<String, Object> data = Map.of("hash", hash, "phone", phone, "attempts", "0");
        redis.opsForHash().putAll(OTP_KEY.formatted(smsId), data);
        redis.expire(OTP_KEY.formatted(smsId), props.otpTtl());
    }

    public void savePlainCode(UUID smsId, String code) {
        redis.opsForValue().set(CODE_KEY.formatted(smsId), code, props.otpTtl());
    }

    public Optional<String> getPlainCode(UUID smsId) {
        Object code = redis.opsForValue().get(CODE_KEY.formatted(smsId));
        return code == null ? Optional.empty() : Optional.of(code.toString());
    }

    public Optional<OtpData> getOtp(UUID smsId) {
        Map<Object, Object> entries = redis.opsForHash().entries(OTP_KEY.formatted(smsId));
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(new OtpData(
            (String) entries.get("hash"),
            (String) entries.get("phone"),
            Integer.parseInt(String.valueOf(entries.get("attempts")))
        ));
    }

    public boolean incrementAttempts(UUID smsId) {
        Long attempts = redis.opsForHash().increment(OTP_KEY.formatted(smsId), "attempts", 1);
        return attempts != null && attempts <= 5;
    }

    public void markUsed(UUID smsId) {
        redis.delete(OTP_KEY.formatted(smsId));
        redis.opsForValue().set(USED_KEY.formatted(smsId), "1", Duration.ofHours(24));
    }

    public boolean isUsed(UUID smsId) {
        return Boolean.TRUE.equals(redis.hasKey(USED_KEY.formatted(smsId)));
    }

    public record OtpData(String hash, String phone, int attempts) {}
}