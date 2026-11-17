package dev.nosurprise.loan.application;

/** Routing keys событий микрозаймов в domain.events (MICROLOANS.md §4.5). */
public final class LoanRoutingKeys {

    public static final String SUBMITTED = "loan.application.submitted";
    public static final String APPROVED = "loan.application.approved";
    public static final String REJECTED = "loan.application.rejected";
    public static final String SIGNED = "loan.application.signed";
    public static final String EXPIRED = "loan.application.expired";
    public static final String REPAID = "loan.application.repaid";
    public static final String CONTRACT_READY = "loan.contract.ready";
    public static final String HISTORY_CHANGED = "loan.history.changed";

    public static final String AGGREGATE = "loan_application";

    private LoanRoutingKeys() {
    }
}
