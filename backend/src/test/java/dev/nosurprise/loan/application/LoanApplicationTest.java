package dev.nosurprise.loan.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanApplicationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final ZoneId MINSK = ZoneId.of("Europe/Minsk");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private static LoanApplication active(int termDays) {
        LoanApplication application = LoanFixtures.submitted(UUID.randomUUID(), "500.00", termDays, "2000.00", NOW);
        application.approve(UUID.randomUUID(), null, NOW, NOW.plus(Duration.ofDays(7)));
        application.activate(NOW.plusSeconds(60), MINSK);
        return application;
    }

    @Test
    @DisplayName("снимок условий и заявителя создаётся при подаче")
    void snapshotOnSubmit() {
        LoanApplication application = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "2000.00", NOW);

        assertThat(application.getStatus()).isEqualTo(LoanStatus.SUBMITTED);
        assertThat(application.getTerms().getTotal()).isEqualByComparingTo("570.00");
        assertThat(application.getApplicant().getMonthlyIncome()).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("недопустимый переход бросает InvalidStatusTransitionException")
    void invalidTransition() {
        LoanApplication application = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "2000.00", NOW);

        assertThatThrownBy(() -> application.markRepaid(NOW, TODAY, TODAY))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> application.markOverdue(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    @DisplayName("подпись после истечения предложения → OfferExpiredException")
    void activateAfterExpiry() {
        LoanApplication application = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "2000.00", NOW);
        application.approve(UUID.randomUUID(), null, NOW, NOW.plus(Duration.ofDays(7)));

        assertThatThrownBy(() -> application.activate(NOW.plus(Duration.ofDays(7)), MINSK))
                .isInstanceOf(OfferExpiredException.class);
    }

    @Test
    @DisplayName("отказ, отмена, истечение и погашение ставят closed_at")
    void closedAt() {
        LoanApplication rejected = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "2000.00", NOW);
        rejected.reject(UUID.randomUUID(), "нет дохода", NOW);
        assertThat(rejected.getClosedAt()).isEqualTo(NOW);
        assertThat(rejected.getDecisionComment()).isEqualTo("нет дохода");

        LoanApplication repaid = active(10);
        repaid.markRepaid(NOW.plusSeconds(120), TODAY, TODAY);
        assertThat(repaid.getStatus()).isEqualTo(LoanStatus.REPAID);
        assertThat(repaid.getClosedAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(repaid.getPaidAt()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("дата возврата = дата подписи по Минску + срок")
    void dueDateFromSigningDateInBusinessZone() {
        // 22:30 UTC — уже следующие сутки в Минске (UTC+3)
        LoanApplication application = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "2000.00", NOW);
        Instant lateEvening = Instant.parse("2026-11-17T22:30:00Z");
        application.approve(UUID.randomUUID(), null, NOW, lateEvening.plus(Duration.ofDays(5)));
        application.activate(lateEvening, MINSK);
        assertThat(application.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 28));
    }

    @Test
    @DisplayName("ACTIVE → OVERDUE → REPAID; дни просрочки по фактической дате оплаты")
    void overdueThenRepaidLate() {
        LoanApplication application = active(10);
        LocalDate due = application.getDueDate();
        assertThat(application.overdueDays(due)).isZero();

        application.markOverdue(NOW);
        assertThat(application.getStatus()).isEqualTo(LoanStatus.OVERDUE);
        assertThat(application.overdueDays(due.plusDays(4))).isEqualTo(4);

        application.markRepaid(NOW, due.plusDays(6), due.plusDays(9));
        assertThat(application.getStatus()).isEqualTo(LoanStatus.REPAID);
        assertThat(application.overdueDays(due.plusDays(30))).isEqualTo(6);
    }

    @Test
    @DisplayName("отмечен из OVERDUE, но оплачен до срока — просрочки нет")
    void repaidBeforeDueDateIsOnTimeEvenFromOverdue() {
        LoanApplication application = active(10);
        LocalDate due = application.getDueDate();
        application.markOverdue(NOW);
        application.markRepaid(NOW, due.minusDays(1), due.plusDays(2));
        assertThat(application.overdueDays(due.plusDays(2))).isZero();
    }

    @Test
    @DisplayName("дата оплаты в будущем — ошибка")
    void paidAtInFutureIsRejected() {
        LoanApplication application = active(10);
        assertThatThrownBy(() -> application.markRepaid(NOW, TODAY.plusDays(1), TODAY))
                .isInstanceOf(InvalidPaidDateException.class);
        assertThat(application.getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    @DisplayName("скоринг: устаревший и повторный ответ не применяются; одобрять можно только после скоринга")
    void scoringIsIdempotentAndGatesApproval() {
        LoanApplication application = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "2000.00", NOW);
        assertThat(application.isScoredForDecision()).isFalse();

        UUID first = UUID.randomUUID();
        application.requestScoring(first, NOW);
        assertThat(application.isScoredForDecision()).isFalse();

        UUID second = UUID.randomUUID();
        application.requestScoring(second, NOW);
        assertThat(application.applyScoring(first, ScoringStatus.COMPLETED, new BigDecimal("0.7"), 280, "B2",
                new BigDecimal("0.02"), ScoringDecision.RECOMMEND, 1, NOW)).isFalse();
        assertThat(application.applyScoring(second, ScoringStatus.COMPLETED, new BigDecimal("0.7"), 280, "B2",
                new BigDecimal("0.02"), ScoringDecision.RECOMMEND, 1, NOW)).isTrue();
        assertThat(application.applyScoring(second, ScoringStatus.COMPLETED, new BigDecimal("0.1"), 40, "E3",
                new BigDecimal("0.7"), ScoringDecision.AUTO_REJECT, 1, NOW)).isFalse();

        assertThat(application.getRatingClass()).isEqualTo("B2");
        assertThat(application.isScoredForDecision()).isTrue();
    }

    @Test
    @DisplayName("OVERDUE — незакрытый статус: новую заявку не подать")
    void overdueCountsAsOpen() {
        assertThat(LoanStatus.OVERDUE.isActive()).isTrue();
        assertThat(LoanStatus.OVERDUE.canTransitionTo(LoanStatus.REPAID)).isTrue();
        assertThat(LoanStatus.ACTIVE.canTransitionTo(LoanStatus.OVERDUE)).isTrue();
    }
}
