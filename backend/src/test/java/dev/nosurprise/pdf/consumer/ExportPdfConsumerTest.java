package dev.nosurprise.pdf.consumer;

import dev.nosurprise.calc.service.CalculationReadModel;
import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.LoanSummary;
import dev.nosurprise.common.idempotency.IdempotencyGuard;
import dev.nosurprise.notification.NotificationService;
import dev.nosurprise.pdf.PdfService;
import dev.nosurprise.pdf.event.ExportPdfRequested;
import dev.nosurprise.scenario.ScenarioExportData;
import dev.nosurprise.scenario.ScenarioService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportPdfConsumerTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID scenarioId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final String calcId = "calc-1";
    private final String processedKey = "pdf:export:processed:" + eventId;

    private ScenarioExportData exportData() {
        return new ScenarioExportData(scenarioId, userId, "Scenario",
                new LoanParams(new java.math.BigDecimal("1000000"), new java.math.BigDecimal("100000"),
                        12, new java.math.BigDecimal("12"), dev.nosurprise.calculator.PaymentType.ANNUITY, List.of()),
                new LoanSummary(new java.math.BigDecimal("80000.00"), new java.math.BigDecimal("10000.00"),
                        new java.math.BigDecimal("960000.00"), 12),
                List.of(), calcId, Instant.now(), null);
    }

    @Test
    @DisplayName("валидное событие генерирует PDF, сохраняет в GridFS, удаляет старые файлы и создаёт notification")
    void success() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        IdempotencyGuard guard = mock(IdempotencyGuard.class);

        when(guard.isDone(processedKey)).thenReturn(false);
        when(guard.tryClaim(processedKey)).thenReturn(true);

        ScenarioExportData data = exportData();
        when(scenarioService.findScenarioExportData(userId, scenarioId)).thenReturn(data);
        when(pdfService.generatePdf(data)).thenReturn(new byte[]{1, 2, 3});
        ObjectId fileId = new ObjectId();
        when(gridFsTemplate.store(any(), anyString(), anyString(), any())).thenReturn(fileId);

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, pdfService, gridFsTemplate, notificationService, guard);
        consumer.on(new ExportPdfRequested(eventId, Instant.now(), scenarioId, calcId, userId));

        verify(gridFsTemplate).store(any(), anyString(), anyString(), any());
        verify(gridFsTemplate).delete(any(Query.class));
        verify(notificationService).createPdfReady(userId, scenarioId, calcId, fileId.toHexString(), "scenario-" + scenarioId + ".pdf");
        verify(guard).complete(processedKey);
    }

    @Test
    @DisplayName("повторный eventId полностью пропускается")
    void duplicateSkipped() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        IdempotencyGuard guard = mock(IdempotencyGuard.class);

        when(guard.isDone(processedKey)).thenReturn(true);

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, pdfService, gridFsTemplate, notificationService, guard);
        consumer.on(new ExportPdfRequested(eventId, Instant.now(), scenarioId, calcId, userId));

        verify(gridFsTemplate, never()).store(any(), anyString(), anyString(), any());
        verify(notificationService, never()).createPdfReady(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("невалидное событие выбрасывает исключение")
    void invalidEventThrows() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        IdempotencyGuard guard = mock(IdempotencyGuard.class);

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, pdfService, gridFsTemplate, notificationService, guard);

        assertThatThrownBy(() -> consumer.on(new ExportPdfRequested(null, Instant.now(), scenarioId, calcId, userId)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("отсутствующий сценарий отмечается обработанным")
    void missingScenarioMarked() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        IdempotencyGuard guard = mock(IdempotencyGuard.class);

        when(guard.isDone(processedKey)).thenReturn(false);
        when(guard.tryClaim(processedKey)).thenReturn(true);
        when(scenarioService.findScenarioExportData(userId, scenarioId))
                .thenThrow(new dev.nosurprise.scenario.ScenarioNotFoundException("not found"));

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, pdfService, gridFsTemplate, notificationService, guard);
        consumer.on(new ExportPdfRequested(eventId, Instant.now(), scenarioId, calcId, userId));

        verify(guard).complete(processedKey);
        verify(gridFsTemplate, never()).store(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("сбой сохранения не отмечает событие обработанным — claim снимается")
    void storageFailureDoesNotMark() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        IdempotencyGuard guard = mock(IdempotencyGuard.class);

        when(guard.isDone(processedKey)).thenReturn(false);
        when(guard.tryClaim(processedKey)).thenReturn(true);

        ScenarioExportData data = exportData();
        when(scenarioService.findScenarioExportData(userId, scenarioId)).thenReturn(data);
        when(pdfService.generatePdf(data)).thenReturn(new byte[]{1, 2, 3});
        when(gridFsTemplate.store(any(), anyString(), anyString(), any())).thenThrow(new RuntimeException("gridfs down"));

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, pdfService, gridFsTemplate, notificationService, guard);

        assertThatThrownBy(() -> consumer.on(new ExportPdfRequested(eventId, Instant.now(), scenarioId, calcId, userId)))
                .isInstanceOf(RuntimeException.class);
        verify(guard).release(processedKey);
        verify(guard, never()).complete(anyString());
    }

    @Test
    @DisplayName("кто-то уже обрабатывает событие (claim не наш) — работа пропускается")
    void claimTakenByOtherConsumer() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        IdempotencyGuard guard = mock(IdempotencyGuard.class);

        when(guard.isDone(processedKey)).thenReturn(false);
        when(guard.tryClaim(processedKey)).thenReturn(false);

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, pdfService, gridFsTemplate, notificationService, guard);
        consumer.on(new ExportPdfRequested(eventId, Instant.now(), scenarioId, calcId, userId));

        verify(scenarioService, never()).findScenarioExportData(any(), any());
        verify(pdfService, never()).generatePdf(any());
        verify(guard, never()).complete(anyString());
    }
}
