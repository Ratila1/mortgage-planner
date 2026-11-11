package dev.nosurprise.pdf.consumer;

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
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class ExportPdfConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportPdfConsumer.class);

    static final String PROCESSED_KEY_PREFIX = "pdf:export:processed:";
    static final Duration PROCESSED_TTL = Duration.ofDays(7);

    private final ScenarioService scenarioService;
    private final PdfService pdfService;
    private final GridFsTemplate gridFsTemplate;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;

    public ExportPdfConsumer(ScenarioService scenarioService, PdfService pdfService,
                             GridFsTemplate gridFsTemplate, NotificationService notificationService,
                             StringRedisTemplate redis) {
        this.scenarioService = scenarioService;
        this.pdfService = pdfService;
        this.gridFsTemplate = gridFsTemplate;
        this.notificationService = notificationService;
        this.redis = redis;
    }

    @RabbitListener(queues = "export.pdf.q")
    public void on(ExportPdfRequested event) {
        validate(event);

        String key = PROCESSED_KEY_PREFIX + event.eventId();
        if (isProcessed(key)) {
            log.info("pdf export event {} already processed, skipping", event.eventId());
            return;
        }

        dev.nosurprise.scenario.ScenarioExportData exportData;
        try {
            exportData = scenarioService.getScenarioExportData(event.scenarioId(), event.userId());
        } catch (dev.nosurprise.scenario.ScenarioNotFoundException ex) {
            log.warn("scenario {} not found for pdf export event {}, skipping", event.scenarioId(), event.eventId());
            markProcessed(key);
            return;
        }

        byte[] pdf = pdfService.generatePdf(exportData);
        String fileName = "scenario-" + exportData.scenarioId() + ".pdf";

        Document metadata = new Document();
        metadata.put("scenarioId", exportData.scenarioId());
        metadata.put("userId", exportData.userId());
        metadata.put("calcId", exportData.calcId());
        metadata.put("fileName", fileName);
        metadata.put("contentType", "application/pdf");
        metadata.put("createdAt", exportData.createdAt());

        ObjectId fileId = gridFsTemplate.store(new ByteArrayInputStream(pdf), fileName, "application/pdf", metadata);

        notificationService.createPdfReady(exportData.userId(), exportData.scenarioId(), exportData.calcId(),
                fileId.toHexString(), fileName);

        markProcessed(key);
    }

    private void validate(ExportPdfRequested event) {
        if (event == null || event.eventId() == null || event.scenarioId() == null
                || event.calcId() == null || event.userId() == null) {
            throw new IllegalArgumentException("invalid ExportPdfRequested event");
        }
    }

    private boolean isProcessed(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key)) || redis.opsForValue().get(key) != null;
    }

    private void markProcessed(String key) {
        redis.opsForValue().setIfAbsent(key, "1", PROCESSED_TTL);
    }
}