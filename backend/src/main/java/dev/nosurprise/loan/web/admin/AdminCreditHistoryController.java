package dev.nosurprise.loan.web.admin;

import dev.nosurprise.loan.history.CreditHistoryQueryService;
import dev.nosurprise.loan.history.CreditHistorySyncService;
import dev.nosurprise.loan.web.dto.CreditHistoryResponse;
import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Кредитная история в админке (§3.12, §4.9). Карточка пользователя (модуль customer) берёт её отдельным
 * запросом: user/customer о займах не знают.
 */
@RestController
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class AdminCreditHistoryController {

    private final CreditHistoryQueryService queries;
    private final CreditHistorySyncService sync;

    public AdminCreditHistoryController(CreditHistoryQueryService queries, CreditHistorySyncService sync) {
        this.queries = queries;
        this.sync = sync;
    }

    @GetMapping("/api/admin/users/{userId}/credit-history")
    CreditHistoryResponse forUser(@PathVariable UUID userId) {
        return CreditHistoryResponse.from(queries.forUser(userId));
    }

    /** Пересобрать коллекцию credit_history из Postgres. Синхронно: заявок немного. */
    @PostMapping("/api/admin/credit-history/rebuild")
    Map<String, Integer> rebuild() {
        return Map.of("synced", sync.rebuildAll());
    }
}
