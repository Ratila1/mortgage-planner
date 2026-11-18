package dev.nosurprise.loan.history;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanEvent;
import dev.nosurprise.loan.application.LoanEventRepository;
import dev.nosurprise.loan.type.LoanTypeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Синхронизация заявки в Mongo (MICROLOANS.md §4.9, путь записи шаги a–c):
 * a) прочитать заявку и весь журнал из Postgres и собрать документ;
 * b) заменить документ в Mongo, если он новее;
 * c) условно сдвинуть отметку credit_history_synced_event_id в Postgres.
 * Шаги идемпотентны сами по себе, поэтому processed_events здесь не нужен.
 * Транзакция Postgres не держится открытой во время записи в Mongo.
 */
@Service
public class CreditHistorySyncService {

    private static final Logger log = LoggerFactory.getLogger(CreditHistorySyncService.class);
    private static final int REBUILD_PAGE = 200;

    private final LoanApplicationRepository applications;
    private final LoanEventRepository events;
    private final LoanTypeService loanTypes;
    private final CreditHistoryStore store;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final Clock clock;

    public CreditHistorySyncService(LoanApplicationRepository applications, LoanEventRepository events,
                                    LoanTypeService loanTypes, CreditHistoryStore store,
                                    PlatformTransactionManager transactionManager, Clock clock) {
        this.applications = applications;
        this.events = events;
        this.loanTypes = loanTypes;
        this.store = store;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void sync(UUID applicationId) {
        CreditHistoryDocument document = readTx.execute(status -> {
            LoanApplication application = applications.findById(applicationId).orElse(null);
            if (application == null) {
                return null;
            }
            List<LoanEvent> journal = events.findByApplicationIdOrderByIdAsc(applicationId);
            String typeName = loanTypes.get(application.getLoanTypeId()).getName();
            return CreditHistoryAssembler.assemble(application, typeName, journal, clock.instant());
        });
        if (document == null) {
            log.warn("credit history sync: application {} not found", applicationId);
            return;
        }
        store.replaceIfNewer(document);
        writeTx.executeWithoutResult(status ->
                applications.markCreditHistorySynced(applicationId, document.lastEventId()));
    }

    /** Пересборка коллекции из Postgres. Коллекцию можно удалить целиком — данные не теряются. */
    public int rebuildAll() {
        int synced = 0;
        for (int page = 0; ; page++) {
            int current = page;
            List<UUID> ids = readTx.execute(status -> applications.findAllIds(PageRequest.of(current, REBUILD_PAGE)));
            if (ids == null || ids.isEmpty()) {
                return synced;
            }
            for (UUID id : ids) {
                sync(id);
                synced++;
            }
        }
    }
}
