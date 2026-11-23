package dev.nosurprise.bureau.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Сохранённая история → ответ банку. Чистая функция: что не попало в StoredHistory, наружу уйти не может. */
public final class CreditReportMapper {

    static final String CURRENCY = "BYN";

    private CreditReportMapper() {
    }

    public static CreditReportResponse found(UUID reportId, SearchQuery.SearchBy matchedBy, StoredHistory history,
                                             Instant generatedAt) {
        Map<String, Integer> byStatus = new TreeMap<>();
        for (StoredHistory.StoredLoan loan : history.loans()) {
            byStatus.merge(loan.status(), 1, Integer::sum);
        }
        List<CreditReportResponse.Loan> loans = history.loans().stream()
                .map(loan -> new CreditReportResponse.Loan(loan.status(),
                        loan.amount() == null ? null : loan.amount().setScale(2).toPlainString(), CURRENCY,
                        loan.submittedAt(), loan.decidedAt(), loan.closedAt()))
                .toList();
        return new CreditReportResponse(reportId, true, matchedBy.name(), loans.size(), byStatus, loans, generatedAt);
    }

    public static CreditReportResponse notFound(UUID reportId, Instant generatedAt) {
        return new CreditReportResponse(reportId, false, null, null, null, null, generatedAt);
    }
}
