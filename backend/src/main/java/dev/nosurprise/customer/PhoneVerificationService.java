package dev.nosurprise.customer;

import dev.nosurprise.common.otp.CodeInvalidException;
import dev.nosurprise.common.otp.OtpCooldown;
import dev.nosurprise.smsclient.SmsClient;
import dev.nosurprise.smsclient.dto.SmsResponse;
import dev.nosurprise.smsclient.dto.VerifyResponse;
import dev.nosurprise.telegram.event.PhoneChangedEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Подтверждение телефона кодом из SMS (MICROLOANS.md §3.13).
 * Код живёт в sms-verification-service; здесь привязка «пользователь → smsId → номер» и пауза 60 с
 * между сообщениями. Каждый запрос уходит со своим Idempotency-Key, иначе sms-сервис вернул бы
 * старую запись и новая SMS не отправилась бы.
 */
@Service
public class PhoneVerificationService {

    static final String SMS_ID_PREFIX = "phone-sms-id:";
    static final String PHONE_KEY_PREFIX = "phone-code-phone:";
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
            // Код привязан и к smsId, и к номеру, на который он реально отправлен.
            redisTemplate.opsForValue().set(SMS_ID_PREFIX + userId, response.getId().toString(), BINDING_TTL);
            redisTemplate.opsForValue().set(PHONE_KEY_PREFIX + userId, profile.phone(), BINDING_TTL);
        } catch (RuntimeException e) {
            // SMS не ушла: пауза не нужна, пользователь может повторить сразу.
            clearBinding(userId);
            cooldown.release(cooldownKey);
            throw e;
        }
    }

    public void confirm(UUID userId, String code) {
        String smsIdValue = redisTemplate.opsForValue().get(SMS_ID_PREFIX + userId);
        String boundPhone = redisTemplate.opsForValue().get(PHONE_KEY_PREFIX + userId);

        if (smsIdValue == null || boundPhone == null) {
            clearBinding(userId);
            throw new CodeInvalidException("code expired or not requested");
        }
        UUID smsId = UUID.fromString(smsIdValue);

        // Сверяем номер до вызова sms-сервиса: старый код не должен сжигать попытки на новом номере.
        ProfileView profile = customerService.requireCompleteProfile(userId);
        if (!boundPhone.equals(profile.phone())) {
            clearBinding(userId);
            throw new CodeInvalidException("phone has changed since the code was sent");
        }

        VerifyResponse response = smsClient.verify(smsId, code);
        if (!response.isVerified()) {
            switch (response.getReason()) {
                case "INVALID_CODE" -> throw new CodeInvalidException("invalid code");
                case "EXPIRED" -> {
                    clearBinding(userId);
                    throw new CodeInvalidException("code expired or not requested");
                }
                case "TOO_MANY_ATTEMPTS" -> {
                    clearBinding(userId);
                    throw new CodeInvalidException("code burned after too many attempts, request a new one");
                }
                case "NOT_READY" -> throw new CodeInvalidException("SMS not yet sent, please wait");
                default -> throw new CodeInvalidException("invalid code");
            }
        }

        customerService.markPhoneVerified(userId, boundPhone);
        clearBinding(userId);
    }

    /** Смена номера в профиле делает сохранённую привязку кода недействительной и снимает паузу SMS. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPhoneChanged(PhoneChangedEvent event) {
        clearBinding(event.userId());
        cooldown.release(COOLDOWN_KEY_PREFIX + event.userId());
    }

    private void clearBinding(UUID userId) {
        redisTemplate.delete(List.of(SMS_ID_PREFIX + userId, PHONE_KEY_PREFIX + userId));
    }
}
