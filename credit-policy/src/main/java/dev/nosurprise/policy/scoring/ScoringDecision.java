package dev.nosurprise.policy.scoring;

/** Рекомендация сервиса (CREDIT_POLICY.md §2). Статусы заявки меняет только монолит. */
public enum ScoringDecision {
    /** К админу с рекомендацией одобрить. */
    RECOMMEND,
    /** К админу: высокий риск или скорбалл не рассчитан. */
    REVIEW,
    /** Отказ системой без участия админа. */
    AUTO_REJECT
}
