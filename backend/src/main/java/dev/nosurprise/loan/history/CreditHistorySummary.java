package dev.nosurprise.loan.history;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Сводка кредитной истории для админа (§3.12): подсказка для решения, автоотказа по ней нет.
 * Считается по документам клиента в памяти: у клиента десятки займов, а не миллионы.
 */
public record CreditHistorySummary(int totalLoans, Map<String, Integer> byStatus, boolean hasActiveLoan,
                                   BigDecimal lastMonthlyIncome, BigDecimal lastDebtLoadPercent,
                                   BigDecimal minDebtLoadPercent, BigDecimal maxDebtLoadPercent,
                                   Map<String, Integer> incidentsByType, Instant firstSubmittedAt) {

    private static final List<String> ACTIVE = List.of("SUBMITTED", "APPROVED", "ACTIVE");

    public static CreditHistorySummary of(List<CreditHistoryDocument> documents) {
        Map<String, Integer> byStatus = new TreeMap<>();
        Map<String, Integer> incidents = new TreeMap<>();
        for (CreditHistoryDocument document : documents) {
            byStatus.merge(document.status(), 1, Integer::sum);
            for (CreditHistoryDocument.Incident incident : document.incidents()) {
                incidents.merge(incident.type(), 1, Integer::sum);
            }
        }
        CreditHistoryDocument last = documents.stream()
                .max(Comparator.comparing(CreditHistoryDocument::submittedAt)).orElse(null);
        return new CreditHistorySummary(
                documents.size(),
                byStatus,
                documents.stream().anyMatch(document -> ACTIVE.contains(document.status())),
                last == null ? null : last.monthlyIncome(),
                last == null ? null : last.debtLoadPercent(),
                documents.stream().map(CreditHistoryDocument::debtLoadPercent).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null),
                documents.stream().map(CreditHistoryDocument::debtLoadPercent).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null),
                incidents,
                documents.stream().map(CreditHistoryDocument::submittedAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null));
    }
}
