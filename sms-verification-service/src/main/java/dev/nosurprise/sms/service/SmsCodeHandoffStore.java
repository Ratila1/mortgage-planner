package dev.nosurprise.sms.service;

import dev.nosurprise.sms.config.SmsProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Короткоживущий открытый код SMS — только чтобы отдать его второму каналу (Telegram) после
 * подтверждённой доставки. Хэш и попытки живут в {@link OtpChallengeService}.
 */
@Component
public class SmsCodeHandoffStore {

    private static final String CODE_KEY = "sms:otp:code:%s";

    private final StringRedisTemplate redis;
    private final SmsProperties props;

    public SmsCodeHandoffStore(StringRedisTemplate redis, SmsProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void save(UUID smsId, String code) {
        redis.opsForValue().set(CODE_KEY.formatted(smsId), code, props.otpTtl());
    }

    public Optional<String> get(UUID smsId) {
        return Optional.ofNullable(redis.opsForValue().get(CODE_KEY.formatted(smsId)));
    }
}
