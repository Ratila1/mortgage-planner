package dev.nosurprise.smsverificationservice.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.springframework.stereotype.Component;

@Component
public class PhoneNormalizer {

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    public String toE164(String raw) {
        try {
            Phonenumber.PhoneNumber parsed = util.parse(raw, null);
            return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw new dev.nosurprise.smsverificationservice.api.error.ValidationException("Invalid phone number: " + raw, e);
        }
    }
}