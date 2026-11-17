package dev.nosurprise.loan.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreditHistorySummaryTest {

    private static CreditHistoryDocument doc(String status, String debtLoad, Instant submitted, String... incidents) {
        return new CreditHistoryDocument(UUID.randomUUID(), UUID.randomUUID(), 1, 1, status,
                new CreditHistoryDocument.Loan(UUID.randomUUID(), "T", BigDecimal.TEN, 10, BigDecimal.ONE,
                        BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN),
                new BigDecimal("1000"), new BigDecimal(debtLoad), submitted, null, null, null, null, List.of(),
                java.util.Arrays.stream(incidents)
                        .map(type -> new CreditHistoryDocument.Incident(type, submitted, null)).toList(),
                submitted);
    }

    @Test
    @DisplayName("сводка: статусы, активный заём, ПДН мин/макс/последний, проколы по типам")
    void summary() {
        Instant t = Instant.parse("2026-11-01T00:00:00Z");
        CreditHistorySummary summary = CreditHistorySummary.of(List.of(
                doc("EXPIRED", "40.00", t, "OFFER_NOT_SIGNED"),
                doc("REPAID", "20.00", t.plusSeconds(3600)),
                doc("ACTIVE", "60.00", t.plusSeconds(7200), "SIGN_CODE_FAILED", "SIGN_CODE_FAILED")));

        assertThat(summary.totalLoans()).isEqualTo(3);
        assertThat(summary.byStatus()).containsEntry("EXPIRED", 1).containsEntry("ACTIVE", 1);
        assertThat(summary.hasActiveLoan()).isTrue();
        assertThat(summary.lastDebtLoadPercent()).isEqualByComparingTo("60.00");
        assertThat(summary.minDebtLoadPercent()).isEqualByComparingTo("20.00");
        assertThat(summary.maxDebtLoadPercent()).isEqualByComparingTo("60.00");
        assertThat(summary.incidentsByType()).containsEntry("SIGN_CODE_FAILED", 2).containsEntry("OFFER_NOT_SIGNED", 1);
        assertThat(summary.firstSubmittedAt()).isEqualTo(t);
    }

    @Test
    @DisplayName("пустая история")
    void empty() {
        CreditHistorySummary summary = CreditHistorySummary.of(List.of());
        assertThat(summary.totalLoans()).isZero();
        assertThat(summary.hasActiveLoan()).isFalse();
        assertThat(summary.lastDebtLoadPercent()).isNull();
    }
}
