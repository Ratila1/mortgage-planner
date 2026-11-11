package dev.nosurprise.pdf.consumer;

import dev.nosurprise.calc.repository.CalculationDocument;
import dev.nosurprise.calc.repository.CalculationRepository;
import dev.nosurprise.notification.NotificationService;
import dev.nosurprise.pdf.PdfService;
import dev.nosurprise.pdf.event.ExportPdfRequested;
import dev.nosurprise.scenario.Scenario;
import dev.nosurprise.scenario.ScenarioService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Optional;
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

    @Test
    @DisplayName("валидное событие генерирует PDF, сохраняет в GridFS и создаёт notification")
    void success() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        CalculationRepository calculationRepository = mock(CalculationRepository.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);

        when(redis.hasKey("pdf:export:processed:" + eventId)).thenReturn(false);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("pdf:export:processed:" + eventId)).thenReturn(null);
        when(ops.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        Scenario scenario = mock(Scenario.class);
        when(scenario.getId()).thenReturn(scenarioId);
        when(scenarioService.findByIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(scenario));

        CalculationDocument calc = mock(CalculationDocument.class);
        when(calc.id()).thenReturn(calcId);
        when(calculationRepository.findById(calcId)).thenReturn(Optional.of(calc));

        when(pdfService.generatePdf(calc, scenario)).thenReturn(new byte[]{1, 2, 3});
        ObjectId fileId = new ObjectId();
        when(gridFsTemplate.store(any(), anyString(), anyString(), any())).thenReturn(fileId);

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, calculationRepository, pdfService,
                gridFsTemplate, notificationService, redis);
        consumer.on(new ExportPdfRequested(eventId, Instant.now(), scenarioId, calcId, userId));

        verify(gridFsTemplate).store(any(), anyString(), anyString(), any());
        verify(notificationService).createPdfReady(userId, scenarioId, calcId, fileId.toHexString(), "scenario-" + scenarioId + ".pdf");
        verify(ops).setIfAbsent("pdf:export:processed:" + eventId, "1", java.time.Duration.ofDays(7));
    }

    @Test
    @DisplayName("повторный eventId полностью пропускается")
    void duplicateSkipped() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        CalculationRepository calculationRepository = mock(CalculationRepository.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        when(redis.hasKey("pdf:export:processed:" + eventId)).thenReturn(true);

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, calculationRepository, pdfService,
                gridFsTemplate, notificationService, redis);
        consumer.on(new ExportPdfRequested(eventId, Instant.now(), scenarioId, calcId, userId));

        verify(gridFsTemplate, never()).store(any(), anyString(), anyString(), any());
        verify(notificationService, never()).createPdfReady(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("невалидное событие выбрасывает исключение")
    void invalidEventThrows() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        CalculationRepository calculationRepository = mock(CalculationRepository.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, calculationRepository, pdfService,
                gridFsTemplate, notificationService, redis);

        assertThatThrownBy(() -> consumer.on(new ExportPdfRequested(null, Instant.now(), scenarioId, calcId, userId)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("отсутствующий сценарий отмечается обработанным")
    void missingScenarioMarked() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        CalculationRepository calculationRepository = mock(CalculationRepository.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);

        when(redis.hasKey("pdf:export:processed:" + eventId)).thenReturn(false);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("pdf:export:processed:" + eventId)).thenReturn(null);
        when(ops.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(scenarioService.findByIdAndUserId(scenarioId, userId)).thenReturn(Optional.empty());

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, calculationRepository, pdfService,
                gridFsTemplate, notificationService, redis);
        consumer.on(new ExportPdfRequested(eventId, Instant.now(), scenarioId, calcId, userId));

        verify(ops).setIfAbsent("pdf:export:processed:" + eventId, "1", java.time.Duration.ofDays(7));
        verify(gridFsTemplate, never()).store(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("сбой сохранения не отмечает событие обработанным")
    void storageFailureDoesNotMark() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        CalculationRepository calculationRepository = mock(CalculationRepository.class);
        PdfService pdfService = mock(PdfService.class);
        GridFsTemplate gridFsTemplate = mock(GridFsTemplate.class);
        NotificationService notificationService = mock(NotificationService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);

        when(redis.hasKey("pdf:export:processed:" + eventId)).thenReturn(false);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("pdf:export:processed:" + eventId)).thenReturn(null);

        Scenario scenario = mock(Scenario.class);
        when(scenario.getId()).thenReturn(scenarioId);
        when(scenarioService.findByIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(scenario));

        CalculationDocument calc = mock(CalculationDocument.class);
        when(calc.id()).thenReturn(calcId);
        when(calculationRepository.findById(calcId)).thenReturn(Optional.of(calc));

        when(pdfService.generatePdf(calc, scenario)).thenReturn(new byte[]{1, 2, 3});
        when(gridFsTemplate.store(any(), anyString(), anyString(), any())).thenThrow(new RuntimeException("gridfs down"));

        ExportPdfConsumer consumer = new ExportPdfConsumer(scenarioService, calculationRepository, pdfService,
                gridFsTemplate, notificationService, redis);

        assertThatThrownBy(() -> consumer.on(new ExportPdfRequested(eventId, Instant.now(), scenarioId, calcId, userId)))
                .isInstanceOf(RuntimeException.class);
        verify(ops, never()).setIfAbsent(anyString(), anyString(), any());
    }
}