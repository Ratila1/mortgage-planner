package dev.nosurprise.sms.service;

import dev.nosurprise.sms.domain.Language;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MessageFormatter {

    private static final Map<Language, String> TEMPLATES = Map.of(
        Language.RU, "Код подтверждения: %s",
        Language.EN, "Verification code: %s"
    );

    public String format(Language lang, String code) {
        return TEMPLATES.get(lang).formatted(code);
    }
}