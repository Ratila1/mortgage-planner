package dev.nosurprise.loan.application;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Статусы заявки и допустимые переходы (MICROLOANS.md §3.8). Таблица переходов — только здесь;
 * сущность меняет статус через свои методы и проверяет переход этим enum-ом.
 */
public enum LoanStatus {
    SUBMITTED,
    APPROVED,
    REJECTED,
    CANCELLED,
    ACTIVE,
    /** Дата возврата прошла, а погашения нет (CREDIT_POLICY.md §5). Переводит ночная задача. */
    OVERDUE,
    EXPIRED,
    REPAID;

    private static final Map<LoanStatus, Set<LoanStatus>> TRANSITIONS = Map.of(
            SUBMITTED, EnumSet.of(CANCELLED, APPROVED, REJECTED),
            APPROVED, EnumSet.of(ACTIVE, EXPIRED),
            ACTIVE, EnumSet.of(REPAID, OVERDUE),
            OVERDUE, EnumSet.of(REPAID),
            REJECTED, EnumSet.noneOf(LoanStatus.class),
            CANCELLED, EnumSet.noneOf(LoanStatus.class),
            EXPIRED, EnumSet.noneOf(LoanStatus.class),
            REPAID, EnumSet.noneOf(LoanStatus.class));

    public boolean canTransitionTo(LoanStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /** Незакрытые статусы: у пользователя не больше одного такого займа (частичный уникальный индекс, V17). */
    public boolean isActive() {
        return this == SUBMITTED || this == APPROVED || this == ACTIVE || this == OVERDUE;
    }

    public boolean isFinal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
