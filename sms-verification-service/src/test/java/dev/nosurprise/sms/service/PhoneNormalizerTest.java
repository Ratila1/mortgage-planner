package dev.nosurprise.sms.service;

import dev.nosurprise.sms.api.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNormalizerTest {

    private final PhoneNormalizer normalizer = new PhoneNormalizer();

    @ParameterizedTest(name = "{0} → +375291234567")
    @DisplayName("номера в разных записях приводятся к E.164")
    @ValueSource(strings = {"+375291234567", "+375 (29) 123-45-67", "+375-29-123-45-67"})
    void normalizesToE164(String raw) {
        assertThat(normalizer.toE164(raw)).isEqualTo("+375291234567");
    }

    @Test
    @DisplayName("номер без плюса и кода страны не разобрать без region → 422")
    void rejectsNationalNumberWithoutRegion() {
        assertThatThrownBy(() -> normalizer.toE164("80291234567"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid phone number");
    }

    @Test
    @DisplayName("номер разобран, но невалиден (несуществующий план нумерации) → 422")
    void rejectsParsedButInvalidNumber() {
        assertThatThrownBy(() -> normalizer.toE164("+11111111111"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid phone number");
    }

    @Test
    @DisplayName("мусор вместо номера → 422")
    void rejectsGarbage() {
        assertThatThrownBy(() -> normalizer.toE164("not-a-phone"))
                .isInstanceOf(ValidationException.class);
    }
}
