package dev.nosurprise.loan.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanApplicationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

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

        assertThatThrownBy(() -> application.markRepaid(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    @DisplayName("подпись после истечения предложения → OfferExpiredException")
    void activateAfterExpiry() {
        LoanApplication application = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "2000.00", NOW);
        application.approve(UUID.randomUUID(), null, NOW, NOW.plus(Duration.ofDays(7)));

        assertThatThrownBy(() -> application.activate(NOW.plus(Duration.ofDays(7))))
                .isInstanceOf(OfferExpiredException.class);
    }

    @Test
    @DisplayName("отказ, отмена, истечение и погашение ставят closed_at")
    void closedAt() {
        LoanApplication rejected = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "2000.00", NOW);
        rejected.reject(UUID.randomUUID(), "нет дохода", NOW);
        assertThat(rejected.getClosedAt()).isEqualTo(NOW);
        assertThat(rejected.getDecisionComment()).isEqualTo("нет дохода");

        LoanApplication repaid = LoanFixtures.submitted(UUID.randomUUID(), "500.00", 10, "2000.00", NOW);
        repaid.approve(UUID.randomUUID(), null, NOW, NOW.plus(Duration.ofDays(7)));
        repaid.activate(NOW.plusSeconds(60));
        repaid.markRepaid(NOW.plusSeconds(120));
        assertThat(repaid.getStatus()).isEqualTo(LoanStatus.REPAID);
        assertThat(repaid.getClosedAt()).isEqualTo(NOW.plusSeconds(120));
    }
}
