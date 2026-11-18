package dev.nosurprise.smsverificationservice.service;

import dev.nosurprise.smsverificationservice.domain.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFormatterTest {

    private final MessageFormatter formatter = new MessageFormatter();

    @Test
    @DisplayName("RU: код в сообщении, длина в пределах 60 символов")
    void formatsRussian() {
        String message = formatter.format(Language.RU, "123456");

        assertThat(message).contains("123456");
        assertThat(message.codePointCount(0, message.length())).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("EN: код в сообщении, длина в пределах 140 символов")
    void formatsEnglish() {
        String message = formatter.format(Language.EN, "123456");

        assertThat(message).contains("123456").startsWith("Verification code:");
        assertThat(message.codePointCount(0, message.length())).isLessThanOrEqualTo(140);
    }
}
