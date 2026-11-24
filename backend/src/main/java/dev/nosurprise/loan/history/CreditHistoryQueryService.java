package dev.nosurprise.loan.history;

import org.springframework.stereotype.Service;

import dev.nosurprise.loan.LoanProperties;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Кредитная история клиента для админа: документы из Mongo и сводка (§3.12). Копия отстаёт на секунды. */
@Service
public class CreditHistoryQueryService {

    private final CreditHistoryStore store;
    private final LoanProperties properties;
    private final Clock clock;

    public CreditHistoryQueryService(CreditHistoryStore store, LoanProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public CreditHistoryView forUser(UUID userId) {
        List<CreditHistoryDocument> documents = store.findByUser(userId);
        LocalDate today = LocalDate.ofInstant(clock.instant(), properties.businessZone());
        return new CreditHistoryView(CreditHistorySummary.of(documents, today), documents);
    }

    public record CreditHistoryView(CreditHistorySummary summary, List<CreditHistoryDocument> loans) {
    }
}
