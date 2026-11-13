package dev.nosurprise.pdf;

import dev.nosurprise.notification.NotificationService;
import dev.nosurprise.scenario.event.ScenarioDeleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Жизненный цикл PDF (§8): при удалении сценария удаляются все его файлы в GridFS
 * и PDF_READY-уведомления. Слушает AFTER_COMMIT — сценарий уже удалён в Postgres.
 */
@Component
public class ScenarioPdfCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(ScenarioPdfCleanupListener.class);

    private final GridFsTemplate gridFsTemplate;
    private final NotificationService notificationService;

    public ScenarioPdfCleanupListener(GridFsTemplate gridFsTemplate, NotificationService notificationService) {
        this.gridFsTemplate = gridFsTemplate;
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ScenarioDeleted event) {
        UUID scenarioId = event.scenarioId();
        try {
            gridFsTemplate.delete(Query.query(Criteria.where("metadata.scenarioId").is(scenarioId.toString())));
        } catch (RuntimeException ex) {
            log.warn("failed to delete pdf files for scenario {}", scenarioId, ex);
        }
        try {
            notificationService.deletePdfReadyForScenario(event.userId(), scenarioId);
        } catch (RuntimeException ex) {
            log.warn("failed to delete pdf-ready notifications for scenario {}", scenarioId, ex);
        }
    }
}
