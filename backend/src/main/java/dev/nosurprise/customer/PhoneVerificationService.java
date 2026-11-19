package dev.nosurprise.customer;

import dev.nosurprise.common.otp.CodeInvalidException;
import dev.nosurprise.common.otp.OtpCooldown;
import dev.nosurprise.smsclient.SmsClient;
import dev.nosurprise.smsclient.dto.SmsResponse;
import dev.nosurprise.smsclient.dto.VerifyResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Подтверждение телефона кодом из SMS (MICROLOANS.md §3.13).
 * Код живёт в sms-verification-service; здесь только привязка «пользователь → smsId» и пауза 60 с
 * между сообщениями. Каждый запрос уходит со своим Idempotency-Key, иначе sms-сервис вернул бы
 * старую запись и новая SMS не отправилась бы.
 */
@Service
public class PhoneVerificationService {

    static final String SMS_ID_PREFIX = "phone-sms-id:";
    static final String COOLDOWN_KEY_PREFIX = "phone-otp-cooldown:";
    private static final String IDEMPOTENCY_KEY_PREFIX = "phone-verification:";
    /** TTL привязки совпадает с TTL OTP в sms-verification-service (5 минут). */
    private static final Duration BINDING_TTL = Duration.ofMinutes(5);

    private final CustomerService customerService;
    private final SmsClient smsClient;
    private final OtpCooldown cooldown;
    private final StringRedisTemplate redisTemplate;

    public PhoneVerificationService(CustomerService customerService, SmsClient smsClient, OtpCooldown cooldown,
                                    StringRedisTemplate redisTemplate) {
        this.customerService = customerService;
        this.smsClient = smsClient;
        this.cooldown = cooldown;
        this.redisTemplate = redisTemplate;
    }

    public void requestCode(UUID userId) {
        ProfileView profile = customerService.requireCompleteProfile(userId);
        String cooldownKey = COOLDOWN_KEY_PREFIX + userId;
        cooldown.tryAcquire(cooldownKey);

        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + userId + ":" + UUID.randomUUID();
        try {
            SmsResponse response = smsClient.createSms(idempotencyKey, profile.phone());
            redisTemplate.opsForValue().set(SMS_ID_PREFIX + userId, response.getId().toString(), BINDING_TTL);
        } catch (RuntimeException e) {
            // SMS не ушла: пауза не нужна, пользователь может повторить сразу.
            cooldown.release(cooldownKey);
            throw e;
        }
    }

    public void confirm(UUID userId, String code) {
        String smsIdKey = SMS_ID_PREFIX + userId;
        String smsIdValue = redisTemplate.opsForValue().get(smsIdKey);

        if (smsIdValue == null) {
            throw new CodeInvalidException("code expired or not requested");
        }
        UUID smsId = UUID.fromString(smsIdValue);

        VerifyResponse response = smsClient.verify(smsId, code);

        if (!response.isVerified()) {
            switch (response.getReason()) {
                case "INVALID_CODE" -> throw new CodeInvalidException("invalid code");
                case "EXPIRED" -> {
                    redisTemplate.delete(smsIdKey);
                    throw new CodeInvalidException("code expired or not requested");
                }
                case "TOO_MANY_ATTEMPTS" -> {
                    redisTemplate.delete(smsIdKey);
                    throw new CodeInvalidException("code burned after too many attempts, request a new one");
                }
                case "NOT_READY" -> throw new CodeInvalidException("SMS not yet sent, please wait");
                default -> throw new CodeInvalidException("invalid code");
            }
        }

        ProfileView profile = customerService.requireCompleteProfile(userId);
        customerService.markPhoneVerified(userId, profile.phone());
        redisTemplate.delete(smsIdKey);
    }
}
