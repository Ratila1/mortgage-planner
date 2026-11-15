package dev.nosurprise.pdf.consumer;

import dev.nosurprise.common.idempotency.IdempotencyGuard;
import dev.nosurprise.notification.NotificationService;
import dev.nosurprise.pdf.PdfService;
import dev.nosurprise.pdf.event.ExportPdfRequested;
import dev.nosurprise.scenario.ScenarioExportData;
import dev.nosurprise.scenario.ScenarioService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.UUID;

/**
 * Генерация PDF по запросу экспорта (§8). Идемпотентность по eventId:
 * claim (SET NX) до работы, release при ошибке, complete после —
 * повторная доставка не создаёт второй файл в GridFS.
 */
@Component
public class ExportPdfConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportPdfConsumer.class);

    static final String PROCESSED_KEY_PREFIX = "pdf:export:processed:";

    private final ScenarioService scenarioService;
    private final PdfService pdfService;
    private final GridFsTemplate gridFsTemplate;
    private final NotificationService notificationService;
    private final IdempotencyGuard guard;

    public ExportPdfConsumer(ScenarioService scenarioService, PdfService pdfService,
                             GridFsTemplate gridFsTemplate, NotificationService notificationService,
                             IdempotencyGuard guard) {
        this.scenarioService = scenarioService;
        this.pdfService = pdfService;
        this.gridFsTemplate = gridFsTemplate;
        this.notificationService = notificationService;
        this.guard = guard;
    }

    @RabbitListener(queues = "export.pdf.q")
    public void on(ExportPdfRequested event) {
        validate(event);

        String key = PROCESSED_KEY_PREFIX + event.eventId();
        if (guard.isDone(key)) {
            log.info("pdf export event {} already processed, skipping", event.eventId());
            return;
        }
        if (!guard.tryClaim(key)) {
            log.info("pdf export event {} is being processed elsewhere, skipping", event.eventId());
            return;
        }
        try {
            dev.nosurprise.scenario.ScenarioExportData exportData;
            try {
                exportData = scenarioService.findScenarioExportData(event.userId(), event.scenarioId());
            } catch (dev.nosurprise.scenario.ScenarioNotFoundException ex) {
                log.warn("scenario {} not found for pdf export event {}, skipping", event.scenarioId(), event.eventId());
                guard.complete(key);
                return;
            }

            byte[] pdf = pdfService.generatePdf(exportData);
            String fileName = "scenario-" + exportData.scenarioId() + ".pdf";

            Document metadata = new Document();
            metadata.put("scenarioId", exportData.scenarioId().toString());
            metadata.put("userId", exportData.userId().toString());
            metadata.put("calcId", exportData.calcId());
            metadata.put("fileName", fileName);
            metadata.put("contentType", "application/pdf");
            metadata.put("createdAt", exportData.createdAt());

            ObjectId fileId = gridFsTemplate.store(new ByteArrayInputStream(pdf), fileName, "application/pdf", metadata);
            deletePreviousPdfFiles(exportData.scenarioId(), fileId);

            notificationService.createPdfReady(exportData.userId(), exportData.scenarioId(), exportData.calcId(),
                    fileId.toHexString(), fileName);

            guard.complete(key);
        } catch (RuntimeException ex) {
            guard.release(key);
            throw ex;
        }
    }

    /**
     * Повторный экспорт не должен оставлять старые файлы (§8): иначе
     * GET /pdf по scenarioId вернёт произвольную (возможно устаревшую) версию.
     */
    private void deletePreviousPdfFiles(UUID scenarioId, ObjectId keepFileId) {
        try {
            gridFsTemplate.delete(new Query(Criteria.where("metadata.scenarioId").is(scenarioId.toString())
                    .and("_id").ne(keepFileId)));
        } catch (RuntimeException ex) {
            log.warn("failed to delete previous pdf files for scenario {}", scenarioId, ex);
        }
    }

    private void validate(ExportPdfRequested event) {
        if (event == null || event.eventId() == null || event.scenarioId() == null
                || event.calcId() == null || event.userId() == null) {
            throw new IllegalArgumentException("invalid ExportPdfRequested event");
        }
    }
}
