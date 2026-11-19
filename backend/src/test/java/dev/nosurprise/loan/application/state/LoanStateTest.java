package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanStatus;
import dev.nosurprise.loan.application.OfferExpiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты отдельных состояний паттерна State: переходы, валидация и действия при входе.
 */
@ExtendWith(MockitoExtension.class)
class LoanStateTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    @Test
    void submittedAllowsCancelApprovedRejected() {
        assertThat(SubmittedState.INSTANCE.allowedTransitions())
                .containsExactlyInAnyOrder(LoanStatus.CANCELLED, LoanStatus.APPROVED, LoanStatus.REJECTED);
        assertThat(SubmittedState.INSTANCE.isActive()).isTrue();
        assertThat(SubmittedState.INSTANCE.isFinal()).isFalse();
    }

    @Test
    void approvedAllowsActiveAndExpired() {
        assertThat(ApprovedState.INSTANCE.allowedTransitions())
                .containsExactlyInAnyOrder(LoanStatus.ACTIVE, LoanStatus.EXPIRED);
        assertThat(ApprovedState.INSTANCE.isActive()).isTrue();
        assertThat(ApprovedState.INSTANCE.isFinal()).isFalse();
    }

    @Test
    void activeAllowsOnlyRepaid() {
        assertThat(ActiveState.INSTANCE.allowedTransitions()).containsExactly(LoanStatus.REPAID);
        assertThat(ActiveState.INSTANCE.isActive()).isTrue();
    }

    @Test
    void finalStatesHaveNoTransitions() {
        assertThat(RejectedState.INSTANCE.allowedTransitions()).isEmpty();
        assertThat(CancelledState.INSTANCE.allowedTransitions()).isEmpty();
        assertThat(ExpiredState.INSTANCE.allowedTransitions()).isEmpty();
        assertThat(RepaidState.INSTANCE.allowedTransitions()).isEmpty();

        assertThat(RejectedState.INSTANCE.isFinal()).isTrue();
        assertThat(CancelledState.INSTANCE.isFinal()).isTrue();
        assertThat(ExpiredState.INSTANCE.isFinal()).isTrue();
        assertThat(RepaidState.INSTANCE.isFinal()).isTrue();
    }

    @Test
    void approvedToActiveRejectsWhenOfferExpired() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.isOfferExpired(NOW)).thenReturn(true);

        assertThatThrownBy(() -> ApprovedState.INSTANCE.validateTransition(application, LoanStatus.ACTIVE, NOW))
                .isInstanceOf(OfferExpiredException.class);
    }

    @Test
    void approvedToActiveAllowedWhenOfferNotExpired() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.isOfferExpired(NOW)).thenReturn(false);

        ApprovedState.INSTANCE.validateTransition(application, LoanStatus.ACTIVE, NOW);
    }

    @Test
    void finalStateOnEnterSetsClosedAt() {
        LoanApplication application = mock(LoanApplication.class);
        UUID actorId = UUID.randomUUID();

        RejectedState.INSTANCE.onEnter(application, actorId, NOW);
        CancelledState.INSTANCE.onEnter(application, actorId, NOW);
        ExpiredState.INSTANCE.onEnter(application, actorId, NOW);
        RepaidState.INSTANCE.onEnter(application, actorId, NOW);

        verify(application, org.mockito.Mockito.times(4)).setClosedAt(NOW);
    }

    @Test
    void stateOfReturnsCorrectInstance() {
        assertThat(LoanState.of(LoanStatus.SUBMITTED)).isSameAs(SubmittedState.INSTANCE);
        assertThat(LoanState.of(LoanStatus.APPROVED)).isSameAs(ApprovedState.INSTANCE);
        assertThat(LoanState.of(LoanStatus.ACTIVE)).isSameAs(ActiveState.INSTANCE);
        assertThat(LoanState.of(LoanStatus.REJECTED)).isSameAs(RejectedState.INSTANCE);
        assertThat(LoanState.of(LoanStatus.CANCELLED)).isSameAs(CancelledState.INSTANCE);
        assertThat(LoanState.of(LoanStatus.EXPIRED)).isSameAs(ExpiredState.INSTANCE);
        assertThat(LoanState.of(LoanStatus.REPAID)).isSameAs(RepaidState.INSTANCE);
    }
}
