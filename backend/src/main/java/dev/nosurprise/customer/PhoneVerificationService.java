package dev.nosurprise.customer;

import dev.nosurprise.common.otp.CodeInvalidException;
import dev.nosurprise.common.otp.OneTimeCodeStore;
import dev.nosurprise.common.otp.OneTimeCodeStore.CodeCheck;
import dev.nosurprise.common.sms.SmsDeliveryException;
import dev.nosurprise.common.sms.SmsSender;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Подтверждение телефона кодом из SMS прямо в форме заявки (MICROLOANS.md §3.3, шаг 3).
 * Без подтверждённого телефона нельзя подписать договор: иначе подпись кодом ничего не доказывает.
 */
@Service
public class PhoneVerificationService {

    static final String CODE_KEY_PREFIX = "phone-otp:";
    /** Пауза на отправку кода подтверждения телефона (собственная, не блокирует подпись договора). */
    public static final String COOLDOWN_KEY_PREFIX = "phone-otp-cooldown:";
    private static final String BOUND_PHONE = "phone";

    private final CustomerService customerService;
    private final OneTimeCodeStore codes;
    private final SmsSender sms;

    public PhoneVerificationService(CustomerService customerService, OneTimeCodeStore codes, SmsSender sms) {
        this.customerService = customerService;
        this.codes = codes;
        this.sms = sms;
    }

    public void requestCode(UUID userId) {
        ProfileView profile = customerService.requireCompleteProfile(userId);
        String key = CODE_KEY_PREFIX + userId;
        String cooldownKey = COOLDOWN_KEY_PREFIX + userId;
        String code = codes.issue(key, cooldownKey, Map.of(BOUND_PHONE, profile.phone()));
        try {
            sms.send(profile.phone(), "Код подтверждения телефона: " + code);
        } catch (SmsDeliveryException ex) {
            codes.revoke(key, cooldownKey);
            throw ex;
        }
    }

    public void confirm(UUID userId, String code) {
        String key = CODE_KEY_PREFIX + userId;
        CodeCheck check = codes.verify(key, code);
        switch (check.result()) {
            case OK -> {
                customerService.markPhoneVerified(userId, check.bound(BOUND_PHONE).orElse(""));
                codes.delete(key);
            }
            case BURNED -> throw new CodeInvalidException("code burned after too many attempts, request a new one");
            case MISSING -> throw new CodeInvalidException("code expired or not requested");
            default -> throw new CodeInvalidException("invalid code");
        }
    }
}
