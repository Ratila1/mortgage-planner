package dev.nosurprise.mortgage.pdf;

import dev.nosurprise.mortgage.calculator.ScheduleEntry;
import dev.nosurprise.mortgage.user.Scenario;
import dev.nosurprise.mortgage.user.ScenarioRepository;
import dev.nosurprise.mortgage.user.ScheduleRepository;
import dev.nosurprise.mortgage.user.ScheduleDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PdfExportServiceImpl implements PdfExportService {

    private static final Logger log = LoggerFactory.getLogger(PdfExportServiceImpl.class);

    private final PdfExportTaskRepository taskRepository;
    private final ScenarioRepository scenarioRepository;
    private final ScheduleRepository scheduleRepository;
    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;
    private final Path storagePath;

    public PdfExportServiceImpl(PdfExportTaskRepository taskRepository,
                                ScenarioRepository scenarioRepository,
                                ScheduleRepository scheduleRepository,
                                RabbitTemplate rabbitTemplate,
                                @Value("${rabbitmq.exchange.pdf-export}") String exchange,
                                @Value("${rabbitmq.routing-key.pdf-export}") String routingKey,
                                @Value("${pdf.storage.path:/app/pdf-exports}") String storagePath) {
        this.taskRepository = taskRepository;
        this.scenarioRepository = scenarioRepository;
        this.scheduleRepository = scheduleRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.storagePath = Paths.get(storagePath);
        initializeStorageDirectory();
    }

    // Package-private constructor for tests (backward compatibility)
    PdfExportServiceImpl(PdfExportTaskRepository taskRepository,
                         ScenarioRepository scenarioRepository,
                         ScheduleRepository scheduleRepository,
                         RabbitTemplate rabbitTemplate,
                         String exchange,
                         String routingKey) {
        this(taskRepository, scenarioRepository, scheduleRepository, rabbitTemplate, exchange, routingKey, "/app/pdf-exports");
    }

    private void initializeStorageDirectory() {
        try {
            Files.createDirectories(storagePath);
            log.info("PDF storage directory initialized at: {}", storagePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create PDF storage directory: {}", storagePath, e);
            throw new IllegalStateException("Cannot initialize PDF storage directory", e);
        }
    }

    @Override
    public Mono<PdfExportTask> createExportTask(String userId, String scenarioId) {
        PdfExportTask task = PdfExportTask.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .scenarioId(scenarioId)
                .status(PdfExportTask.TaskStatus.PENDING)
                .build();

        return taskRepository.save(task)
                .doOnSuccess(t -> {
                    rabbitTemplate.convertAndSend(exchange, routingKey, new PdfExportMessage(t.id(), userId, scenarioId));
                });
    }

    @Override
    public Mono<PdfExportTask> getTaskStatus(String taskId) {
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new TaskNotFoundException("Task not found")));
    }

    public Mono<Void> processExportTask(PdfExportMessage message) {
        String taskId = message.taskId();
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new TaskNotFoundException("Task not found: " + taskId)))
                .flatMap(task -> {
                    PdfExportTask processingTask = new PdfExportTask(
                            task.id(), task.userId(), task.scenarioId(),
                            PdfExportTask.TaskStatus.PROCESSING, null, null,
                            task.createdAt(), null
                    );
                    return taskRepository.save(processingTask);
                })
                .flatMap(task -> scenarioRepository.findByIdAndUserId(task.scenarioId(), Long.valueOf(task.userId())))
                .switchIfEmpty(Mono.error(new IllegalStateException("Scenario not found")))
                .flatMap(scenario -> scheduleRepository.findByScheduleHash(scenario.scheduleHash())
                        .map(ScheduleDocument::entries)
                        .defaultIfEmpty(List.of())
                        .flatMap(entries -> generatePdf(scenario, entries)))
                .flatMap(pdfBytes -> {
                    String fileName = taskId + ".pdf";
                    Path filePath = storagePath.resolve(fileName);
                    try {
                        Files.write(filePath, pdfBytes);
                    } catch (IOException e) {
                        return Mono.error(new RuntimeException("Failed to write PDF file", e));
                    }
                    return taskRepository.findById(taskId)
                            .flatMap(t -> taskRepository.save(new PdfExportTask(
                                    t.id(), t.userId(), t.scenarioId(),
                                    PdfExportTask.TaskStatus.COMPLETED, filePath.toString(), null,
                                    t.createdAt(), Instant.now()
                            )));
                })
                .onErrorResume(e -> {
                    log.error("PDF export failed for task {}", taskId, e);
                    return taskRepository.findById(taskId)
                            .flatMap(t -> taskRepository.save(new PdfExportTask(
                                    t.id(), t.userId(), t.scenarioId(),
                                    PdfExportTask.TaskStatus.FAILED, null, e.getMessage(),
                                    t.createdAt(), Instant.now()
                            )));
                })
                .then();
    }

    private Mono<byte[]> generatePdf(Scenario scenario, List<ScheduleEntry> entries) {
        return Mono.fromCallable(() -> {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);

                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    float margin = 50;
                    float yStart = page.getMediaBox().getHeight() - margin;
                    float tableTop = yStart - 120;
                    float rowHeight = 18;
                    float colWidth = (page.getMediaBox().getWidth() - 2 * margin) / 5;

                    // Title
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                    contentStream.beginText();
                    contentStream.newLineAtOffset(margin, yStart);
                    contentStream.showText("График платежей по ипотеке");
                    contentStream.endText();

                    // Scenario info
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
                    float y = yStart - 25;
                    contentStream.beginText();
                    contentStream.newLineAtOffset(margin, y);
                    contentStream.showText("Сценарий: " + scenario.name());
                    contentStream.endText();

                    y -= 14;
                    contentStream.beginText();
                    contentStream.newLineAtOffset(margin, y);
                    contentStream.showText("Стоимость: %,.2f ₽ | Взнос: %,.2f ₽ | Срок: %d мес | Ставка: %.2f%% | Тип: %s"
                            .formatted(scenario.propertyPrice(), scenario.downPayment(), scenario.termMonths(),
                                    scenario.rate().multiply(BigDecimal.valueOf(100)), scenario.paymentType()));
                    contentStream.endText();

                    y -= 14;
                    contentStream.beginText();
                    contentStream.newLineAtOffset(margin, y);
                    contentStream.showText("Ежемесячный платёж: %,.2f ₽ | Переплата: %,.2f ₽ | Всего: %,.2f ₽"
                            .formatted(scenario.monthlyPayment(), scenario.overpayment(), scenario.totalPayment()));
                    contentStream.endText();

                    // Table header
                    y = tableTop;
                    drawTableHeader(contentStream, margin, y, colWidth, rowHeight);

                    // Table rows
                    int maxRows = Math.min(entries.size(), 60); // Limit rows per page
                    for (int i = 0; i < maxRows; i++) {
                        ScheduleEntry entry = entries.get(i);
                        y -= rowHeight;
                        if (y < margin + 20) {
                            document.addPage(new PDPage(PDRectangle.A4));
                            y = page.getMediaBox().getHeight() - margin;
                            drawTableHeader(contentStream, margin, y, colWidth, rowHeight);
                        }
                        drawTableRow(contentStream, margin, y, colWidth, rowHeight,
                                entry.month(),
                                entry.payment(),
                                entry.principal(),
                                entry.interest(),
                                entry.remaining());
                    }

                    if (entries.size() > maxRows) {
                        y -= rowHeight;
                        if (y < margin + 20) {
                            document.addPage(new PDPage(PDRectangle.A4));
                            y = page.getMediaBox().getHeight() - margin;
                        }
                        contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE), 9);
                        contentStream.beginText();
                        contentStream.newLineAtOffset(margin, y);
                        contentStream.showText("... и ещё " + (entries.size() - maxRows) + " строк");
                        contentStream.endText();
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    document.save(baos);
                    return baos.toByteArray();
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to generate PDF", e);
            }
        });
    }

    private void drawTableHeader(PDPageContentStream cs, float x, float y, float colWidth, float rowHeight) throws IOException {
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 7);
        String[] headers = {"Месяц", "Платёж", "Основной долг", "Проценты", "Остаток"};
        for (int i = 0; i < headers.length; i++) {
            float cx = x + i * colWidth;
            cs.addRect(cx, y, colWidth, rowHeight);
            cs.beginText();
            cs.newLineAtOffset(cx + 2, y + 4);
            cs.showText(headers[i]);
            cs.endText();
        }
        cs.stroke();
    }

    private void drawTableRow(PDPageContentStream cs, float x, float y, float colWidth, float rowHeight,
                               int month, BigDecimal payment, BigDecimal principal, BigDecimal interest, BigDecimal remaining) throws IOException {
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 7);
        String[] values = {
                String.valueOf(month),
                String.format("%,.2f", payment),
                String.format("%,.2f", principal),
                String.format("%,.2f", interest),
                String.format("%,.2f", remaining)
        };
        for (int i = 0; i < values.length; i++) {
            float cx = x + i * colWidth;
            cs.addRect(cx, y, colWidth, rowHeight);
            cs.beginText();
            cs.newLineAtOffset(cx + 2, y + 4);
            cs.showText(values[i]);
            cs.endText();
        }
        cs.stroke();
    }

    public record PdfExportMessage(String taskId, String userId, String scenarioId) {}

    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String message) { super(message); }
    }
}







