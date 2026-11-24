package dev.nosurprise.sms.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import dev.nosurprise.sms.api.error.ValidationException;
import org.springframework.stereotype.Component;

@Component
public class PhoneNormalizer {

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    /**
     * Номер приводится к E.164 и проверяется на валидность: разбор пропускает технически
     * невозможные номера (неверная длина, несуществующий план нумерации), отправлять на них SMS нельзя.
     * Регион по умолчанию не задан осознанно: сервис принимает только международный формат.
     */
    public String toE164(String raw) {
        try {
            Phonenumber.PhoneNumber parsed = util.parse(raw, null);
            if (!util.isValidNumber(parsed)) {
                throw new ValidationException("Invalid phone number: " + raw);
            }
            return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw new ValidationException("Invalid phone number: " + raw, e);
        }
    }
}
