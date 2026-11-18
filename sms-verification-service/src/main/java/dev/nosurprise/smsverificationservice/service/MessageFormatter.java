package dev.nosurprise.smsverificationservice.service;

import dev.nosurprise.smsverificationservice.domain.Language;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MessageFormatter {

    private static final Map<Language, String> TEMPLATES = Map.of(
        Language.RU, "Код подтверждения: %s",
        Language.EN, "Verification code: %s"
    );
    private static final Map<Language, Integer> LIMITS = Map.of(
        Language.RU, 60, Language.EN, 140
    );

    public String format(Language lang, String code) {
        String msg = TEMPLATES.get(lang).formatted(code);
        int len = msg.codePointCount(0, msg.length());
        if (len > LIMITS.get(lang)) {
            throw new IllegalStateException(
                "Template exceeds limit for " + lang + ": " + len);
        }
        return msg;
    }
}