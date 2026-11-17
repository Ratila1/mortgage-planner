package dev.nosurprise.loan.history;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanEvent;
import dev.nosurprise.loan.application.LoanEventType;
import dev.nosurprise.loan.application.LoanFixtures;
import dev.nosurprise.loan.application.LoanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreditHistoryAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    @Test
    @DisplayName("хронология — из STATUS_CHANGED, проколы — из инцидентов, lastEventId — максимальный id журнала")
    void assemblesTimelineAndIncidents() {
        UUID user = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        LoanApplication application = LoanFixtures.submitted(user, "500.00", 10, "1000.00", NOW);
        UUID id = application.getId();
        List<LoanEvent> events = List.of(
                LoanFixtures.event(11, id, LoanEventType.STATUS_CHANGED, LoanStatus.SUBMITTED, user, NOW),
                LoanFixtures.event(12, id, LoanEventType.STATUS_CHANGED, LoanStatus.APPROVED, admin, NOW.plusSeconds(60)),
                LoanFixtures.event(15, id, LoanEventType.SIGN_CODE_SENT, null, user, NOW.plusSeconds(120)),
                LoanFixtures.event(17, id, LoanEventType.SIGN_CODE_FAILED, null, user, NOW.plusSeconds(180)));

        CreditHistoryDocument document = CreditHistoryAssembler.assemble(application, "До зарплаты", events, NOW);

        assertThat(document.id()).isEqualTo(id);
        assertThat(document.userId()).isEqualTo(user);
        assertThat(document.lastEventId()).isEqualTo(17);
        assertThat(document.timeline()).extracting(CreditHistoryDocument.TimelineEntry::status)
                .containsExactly("SUBMITTED", "APPROVED");
        assertThat(document.incidents()).extracting(CreditHistoryDocument.Incident::type)
                .containsExactly("SIGN_CODE_FAILED");
        // 570 / 1000 = 57 %
        assertThat(document.debtLoadPercent()).isEqualByComparingTo("57.00");
        assertThat(document.loan().typeName()).isEqualTo("До зарплаты");
    }

    @Test
    @DisplayName("одинаковый вход — одинаковый документ: повторная сборка безопасна")
    void deterministic() {
        LoanApplication application = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "1000.00", NOW);
        List<LoanEvent> events = List.of(LoanFixtures.event(1, application.getId(), LoanEventType.STATUS_CHANGED,
                LoanStatus.SUBMITTED, application.getUserId(), NOW));

        assertThat(CreditHistoryAssembler.assemble(application, "T", events, NOW))
                .isEqualTo(CreditHistoryAssembler.assemble(application, "T", events, NOW));
    }
}
