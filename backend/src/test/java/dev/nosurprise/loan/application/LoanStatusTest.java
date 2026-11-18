package dev.nosurprise.loan.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Все пары статусов против таблицы переходов §3.8. */
class LoanStatusTest {

    private static final Set<String> ALLOWED = Set.of(
            "SUBMITTED->CANCELLED", "SUBMITTED->APPROVED", "SUBMITTED->REJECTED",
            "APPROVED->ACTIVE", "APPROVED->EXPIRED",
            "ACTIVE->REPAID");

    static Stream<Arguments> allPairs() {
        return Arrays.stream(LoanStatus.values())
                .flatMap(from -> Arrays.stream(LoanStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    @DisplayName("переход разрешён только если он есть в таблице §3.8")
    void transitions(LoanStatus from, LoanStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.contains(from + "->" + to));
    }

    @ParameterizedTest
    @MethodSource("allStatuses")
    @DisplayName("активные статусы — SUBMITTED, APPROVED, ACTIVE; конечные — без исходящих переходов")
    void activeAndFinal(LoanStatus status) {
        assertThat(status.isActive()).isEqualTo(Set.of(LoanStatus.SUBMITTED, LoanStatus.APPROVED, LoanStatus.ACTIVE)
                .contains(status));
        assertThat(status.isFinal()).isEqualTo(Set.of(LoanStatus.REJECTED, LoanStatus.CANCELLED, LoanStatus.EXPIRED,
                LoanStatus.REPAID).contains(status));
    }

    static Stream<LoanStatus> allStatuses() {
        return Arrays.stream(LoanStatus.values());
    }
}
