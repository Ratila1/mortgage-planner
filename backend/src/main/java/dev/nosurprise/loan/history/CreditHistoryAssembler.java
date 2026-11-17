package dev.nosurprise.loan.history;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanEvent;
import dev.nosurprise.loan.application.LoanEventType;
import dev.nosurprise.loan.calc.DebtLoad;

import java.time.Instant;
import java.util.List;

/**
 * Заявка + её журнал loan_events → документ кредитной истории. Чистая функция (§7.5):
 * один и тот же вход всегда даёт один и тот же документ, поэтому повторная сборка безопасна.
 */
public final class CreditHistoryAssembler {

    private CreditHistoryAssembler() {
    }

    public static CreditHistoryDocument assemble(LoanApplication application, String loanTypeName,
                                                 List<LoanEvent> events, Instant syncedAt) {
        long lastEventId = events.stream().mapToLong(LoanEvent::getId).max().orElse(0L);
        var terms = application.getTerms();
        var applicant = application.getApplicant();
        List<CreditHistoryDocument.TimelineEntry> timeline = events.stream()
                .filter(event -> event.getType() == LoanEventType.STATUS_CHANGED)
                .map(event -> new CreditHistoryDocument.TimelineEntry(event.getToStatus().name(),
                        event.getOccurredAt(), event.getActorId()))
                .toList();
        List<CreditHistoryDocument.Incident> incidents = events.stream()
                .filter(event -> event.getType().isIncident())
                .map(event -> new CreditHistoryDocument.Incident(event.getType().name(), event.getOccurredAt(),
                        event.getDetails()))
                .toList();
        return new CreditHistoryDocument(
                application.getId(),
                application.getUserId(),
                CreditHistoryDocument.SCHEMA_VERSION,
                lastEventId,
                application.getStatus().name(),
                new CreditHistoryDocument.Loan(application.getLoanTypeId(), loanTypeName, application.getAmount(),
                        application.getTermDays(), terms.getDailyRate(), terms.getModifier(), terms.getInterest(),
                        terms.getTotal()),
                applicant.getMonthlyIncome(),
                DebtLoad.percent(terms.getTotal(), applicant.getMonthlyIncome()),
                application.getCreatedAt(),
                application.getDecidedAt(),
                application.getDecidedBy(),
                application.getDecisionComment(),
                application.getClosedAt(),
                timeline,
                incidents,
                syncedAt);
    }
}
