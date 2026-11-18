package dev.nosurprise.loan.web.dto;

import dev.nosurprise.loan.history.CreditHistoryDocument;
import dev.nosurprise.loan.history.CreditHistoryQueryService.CreditHistoryView;
import dev.nosurprise.loan.history.CreditHistorySummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Кредитная история для админа (§3.12): сводка и все займы клиента из Mongo. Mongo-документы наружу не отдаём. */
public record CreditHistoryResponse(Summary summary, List<Loan> loans) {

    public static CreditHistoryResponse from(CreditHistoryView view) {
        CreditHistorySummary s = view.summary();
        return new CreditHistoryResponse(
                new Summary(s.totalLoans(), s.byStatus(), s.hasActiveLoan(), str(s.lastMonthlyIncome()),
                        str(s.lastDebtLoadPercent()), str(s.minDebtLoadPercent()), str(s.maxDebtLoadPercent()),
                        s.incidentsByType(), s.firstSubmittedAt()),
                view.loans().stream().map(CreditHistoryResponse::loan).toList());
    }

    private static Loan loan(CreditHistoryDocument d) {
        var l = d.loan();
        return new Loan(d.id(), d.status(), l.typeName(), str(l.amount()), l.termDays(), str(l.dailyRate()),
                str(l.modifier()), str(l.interest()), str(l.total()), str(d.monthlyIncome()),
                str(d.debtLoadPercent()), d.submittedAt(), d.decidedAt(), d.decidedBy(), d.decisionComment(),
                d.closedAt(),
                d.timeline().stream().map(t -> new TimelineEntry(t.status(), t.at(), t.actorId())).toList(),
                d.incidents().stream().map(i -> new Incident(i.type(), i.at())).toList(),
                d.syncedAt());
    }

    private static String str(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    public record Summary(int totalLoans, Map<String, Integer> byStatus, boolean hasActiveLoan,
                          String lastMonthlyIncome, String lastDebtLoadPercent, String minDebtLoadPercent,
                          String maxDebtLoadPercent, Map<String, Integer> incidentsByType, Instant firstSubmittedAt) {
    }

    public record Loan(UUID applicationId, String status, String loanTypeName, String amount, int termDays,
                       String dailyRate, String modifier, String interest, String total, String monthlyIncome,
                       String debtLoadPercent, Instant submittedAt, Instant decidedAt, UUID decidedBy,
                       String decisionComment, Instant closedAt, List<TimelineEntry> timeline,
                       List<Incident> incidents, Instant syncedAt) {
    }

    public record TimelineEntry(String status, Instant at, UUID actorId) {
    }

    public record Incident(String type, Instant at) {
    }
}
