package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Состояние заявки в паттерне State (MICROLOANS.md §3.8).
 * Каждое состояние знает, в какие состояния может перейти,
 * какую валидацию требует переход и какие действия выполнить при входе/выходе.
 * <p>
 * Состояния без собственных полевых данных — реализованы как сингルトны (INSTANCE),
 * так как вся пользовательская информация хранится в контексте {@link LoanApplication}.
 */
public interface LoanState {

    LoanStatus status();

    Set<LoanStatus> allowedTransitions();

    default boolean canTransitionTo(LoanStatus target) {
        return allowedTransitions().contains(target);
    }

    default boolean isActive() {
        return false;
    }

    default boolean isFinal() {
        return allowedTransitions().isEmpty();
    }

    /**
     * Проверка business-правил перед переходом (например, истёк ли срок предложения).
     * Бросает исключение, если переход невозможен.
     *
     * @param context контекст заявки
     * @param target  целевой статус
     * @param now     текущее время
     */
    default void validateTransition(LoanApplication context, LoanStatus target, Instant now) {
        // по умолчанию дополнительных проверок нет
    }

    /**
     * Действия при выходе из состояния.
     */
    default void onExit(LoanApplication context) {
        LoanStateMetrics.recordExit(this, context, java.time.Instant.now());
    }

    /**
     * Действия при входе в состояние (установить closedAt, decidedAt и т.п.).
     */
    default void onEnter(LoanApplication context, UUID actorId, Instant now) {
        LoanStateMetrics.recordEnter(this, context, actorId, now);
    }

    static LoanState of(LoanStatus status) {
        return switch (status) {
            case SUBMITTED -> SubmittedState.INSTANCE;
            case APPROVED -> ApprovedState.INSTANCE;
            case ACTIVE -> ActiveState.INSTANCE;
            case REJECTED -> RejectedState.INSTANCE;
            case CANCELLED -> CancelledState.INSTANCE;
            case EXPIRED -> ExpiredState.INSTANCE;
            case REPAID -> RepaidState.INSTANCE;
        };
    }
}
