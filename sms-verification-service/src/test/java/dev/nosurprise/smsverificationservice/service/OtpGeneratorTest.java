package dev.nosurprise.smsverificationservice.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OtpGeneratorTest {

    private final OtpGenerator generator = new OtpGenerator();

    @Test
    @DisplayName("код всегда ровно 6 цифр, включая ведущие нули")
    void generatesSixDigits() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.generate()).matches("\\d{6}");
        }
    }

    @Test
    @DisplayName("код не константа: за 200 генераций встречается больше одного значения")
    void generatesDifferentCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(generator.generate());
        }
        assertThat(codes).hasSizeGreaterThan(1);
    }
}
