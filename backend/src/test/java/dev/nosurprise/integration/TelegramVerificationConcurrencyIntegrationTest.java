package dev.nosurprise.integration;

import dev.nosurprise.integration.support.AbstractIntegrationTest;
import dev.nosurprise.telegram.TelegramVerification;
import dev.nosurprise.telegram.TelegramVerificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the conditional claim used for idempotent Telegram signing completion. */
class TelegramVerificationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TelegramVerificationRepository verifications;

    @Test
    @Transactional
    @DisplayName("второй compete не может перехватить уже завершённую верификацию")
    void claimCompletionIsAtomic() {
        UUID id = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-01-01T00:00:00Z");
        verifications.save(new TelegramVerification(id, UUID.randomUUID(), "SIGNING",
                UUID.randomUUID().toString(), completedAt.minusSeconds(60), completedAt.plusSeconds(60)));

        assertThat(verifications.claimCompletion(id, completedAt)).isEqualTo(1);
        assertThat(verifications.claimCompletion(id, completedAt.plusSeconds(1))).isZero();

        assertThat(jdbc.queryForObject("select status from telegram_verifications where id = ?",
                String.class, id)).isEqualTo("COMPLETED");
    }
}
