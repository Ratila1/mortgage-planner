package dev.nosurprise.sms.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtpHasherTest {

    private final OtpHasher hasher = new OtpHasher();

    @Test
    @DisplayName("SHA-256 в hex: 64 символа, детерминированный")
    void hashesDeterministically() {
        String hash = hasher.hash("123456");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hasher.hash("123456")).isEqualTo(hash);
    }

    @Test
    @DisplayName("разные коды → разные хеши")
    void differentCodesProduceDifferentHashes() {
        assertThat(hasher.hash("123456")).isNotEqualTo(hasher.hash("654321"));
    }

    @Test
    @DisplayName("verify принимает только исходный код")
    void verifiesOnlyOriginalCode() {
        String hash = hasher.hash("123456");

        assertThat(hasher.verify("123456", hash)).isTrue();
        assertThat(hasher.verify("000000", hash)).isFalse();
    }
}
