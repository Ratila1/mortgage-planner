package dev.nosurprise.loan.application;

/**
 * Состояние скоринга заявки (CREDIT_POLICY.md §6): PENDING — запрос ушёл в credit-policy, ответа ещё нет;
 * COMPLETED — посчитано; NOT_CALCULATED — сервис не смог посчитать (не хватает данных), решает человек.
 */
public enum ScoringStatus {
    PENDING,
    COMPLETED,
    NOT_CALCULATED
}
