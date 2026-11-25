package dev.nosurprise.loan.application;

/** Routing keys событий микрозаймов в domain.events (MICROLOANS.md §4.5). */
public final class LoanRoutingKeys {

    public static final String SUBMITTED = "loan.application.submitted";
    public static final String APPROVED = "loan.application.approved";
    public static final String REJECTED = "loan.application.rejected";
    public static final String SIGNED = "loan.application.signed";
    public static final String EXPIRED = "loan.application.expired";
    public static final String REPAID = "loan.application.repaid";
    /** Заём просрочен; тем же ключом уходят напоминания на 3 и 7 день (status = OVERDUE_REMINDER). */
    public static final String OVERDUE = "loan.application.overdue";
    public static final String CONTRACT_READY = "loan.contract.ready";
    public static final String HISTORY_CHANGED = "loan.history.changed";
    /** Запрос скоринга в credit-policy и ответ оттуда (CREDIT_POLICY.md §8). */
    public static final String SCORING_REQUESTED = "loan.scoring.requested";
    public static final String SCORING_COMPLETED = "loan.scoring.completed";

    public static final String AGGREGATE = "loan_application";

    private LoanRoutingKeys() {
    }
}
