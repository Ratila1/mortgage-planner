package dev.nosurprise.loan.history;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Кредитная история клиента для админа: документы из Mongo и сводка (§3.12). Копия отстаёт на секунды. */
@Service
public class CreditHistoryQueryService {

    private final CreditHistoryStore store;

    public CreditHistoryQueryService(CreditHistoryStore store) {
        this.store = store;
    }

    public CreditHistoryView forUser(UUID userId) {
        List<CreditHistoryDocument> documents = store.findByUser(userId);
        return new CreditHistoryView(CreditHistorySummary.of(documents), documents);
    }

    public record CreditHistoryView(CreditHistorySummary summary, List<CreditHistoryDocument> loans) {
    }
}
