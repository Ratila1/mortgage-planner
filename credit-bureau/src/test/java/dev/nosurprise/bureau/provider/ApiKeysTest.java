package dev.nosurprise.bureau.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysTest {

    @Test
    @DisplayName("сгенерированный ключ разбирается, хеш совпадает только с его секретом")
    void generateParseMatch() {
        ApiKeys.Generated generated = ApiKeys.generate();

        ApiKeys.Parsed parsed = ApiKeys.parse(generated.plainText()).orElseThrow();

        assertThat(generated.plainText()).startsWith("nsb_" + generated.prefix() + "_");
        assertThat(parsed.prefix()).isEqualTo(generated.prefix());
        assertThat(ApiKeys.matches(parsed.secret(), generated.hash())).isTrue();
        assertThat(ApiKeys.matches(parsed.secret() + "x", generated.hash())).isFalse();
        assertThat(generated.hash()).hasSize(64).doesNotContain(parsed.secret());
    }

    @Test
    @DisplayName("ключи не повторяются")
    void unique() {
        assertThat(ApiKeys.generate().plainText()).isNotEqualTo(ApiKeys.generate().plainText());
    }

    @Test
    @DisplayName("ключ не в формате nsb_<8>_<43> отклоняется до поиска в базе")
    void malformed() {
        assertThat(ApiKeys.parse(null)).isEmpty();
        assertThat(ApiKeys.parse("")).isEmpty();
        assertThat(ApiKeys.parse("nsb_short_abc")).isEmpty();
        assertThat(ApiKeys.parse("nsb_ABCDEFGH_" + "a".repeat(43))).isEmpty();
        assertThat(ApiKeys.parse("nsb_abcdefgh_" + "a".repeat(43))).isPresent();
    }
}
