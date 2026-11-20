package dev.nosurprise.loan.jobs;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanEvent;
import dev.nosurprise.loan.application.LoanEventRepository;
import dev.nosurprise.loan.application.LoanEventType;
import dev.nosurprise.loan.application.LoanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ежедневный дамп метрик переходов состояний заявок (паттерн State).
 * В 23:00 собирает за сутки события STATUS_CHANGED из loan_events, агрегирует
 * количества и суммы и выгружает полный JSON-отчёт в файл.
 */
@Component
public class LoanStateMetricsDumpJob {

    private static final Logger log = LoggerFactory.getLogger(LoanStateMetricsDumpJob.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final LoanApplicationRepository applications;
    private final LoanEventRepository events;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path reportDir;

    @org.springframework.beans.factory.annotation.Autowired
    public LoanStateMetricsDumpJob(LoanApplicationRepository applications, LoanEventRepository events,
                                   ObjectMapper objectMapper, Clock clock,
                                   @Value("${app.loan.state-metrics.report-dir:${java.io.tmpdir}/nosurprise-reports}") String reportDir) {
        this.applications = applications;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.reportDir = Paths.get(reportDir);
    }

    public LoanStateMetricsDumpJob(LoanApplicationRepository applications, LoanEventRepository events,
                                   ObjectMapper objectMapper, Clock clock) {
        this(applications, events, objectMapper, clock, System.getProperty("java.io.tmpdir") + "/nosurprise-reports");
    }

    @Scheduled(cron = "${app.loan.state-metrics.dump-cron:0 0 23 * * *}")
    @Transactional(readOnly = true)
    public Path dumpDailyReport() {
        Map<String, Object> report = buildReport();
        return writeFile(report, clock.instant());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildReport() {
        Instant now = clock.instant();
        Instant dayStart = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dayEnd = dayStart.plusSeconds(86400);

        List<LoanEvent> dayEvents = events.findByTypeAndOccurredAtBetween(LoanEventType.STATUS_CHANGED, dayStart, dayEnd);

        Set<UUID> applicationIds = dayEvents.stream().map(LoanEvent::getApplicationId).collect(Collectors.toSet());
        Map<UUID, LoanApplication> applicationById = applications.findAllById(applicationIds).stream()
                .collect(Collectors.toMap(LoanApplication::getId, a -> a));

        Map<String, Long> countsByTransition = new TreeMap<>();
        Map<String, BigDecimal> amountsByTransition = new TreeMap<>();
        long total = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        List<Map<String, Object>> details = new ArrayList<>();
        for (LoanEvent event : dayEvents) {
            String from = event.getFromStatus() == null ? "NONE" : event.getFromStatus().name();
            String to = event.getToStatus() == null ? "NONE" : event.getToStatus().name();
            String key = from + " -> " + to;
            countsByTransition.merge(key, 1L, Long::sum);

            LoanApplication application = applicationById.get(event.getApplicationId());
            BigDecimal amount = application == null ? null : application.getAmount();
            if (amount != null) {
                amountsByTransition.merge(key, amount, BigDecimal::add);
                totalAmount = totalAmount.add(amount);
            }
            total++;

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("applicationId", event.getApplicationId());
            detail.put("userId", application == null ? null : application.getUserId());
            detail.put("amount", amount == null ? null : amount.toPlainString());
            detail.put("from", from);
            detail.put("to", to);
            detail.put("actorId", event.getActorId());
            detail.put("occurredAt", event.getOccurredAt().toString());
            details.add(detail);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("date", DATE.format(dayStart));
        report.put("generatedAt", now.toString());
        report.put("totalTransitions", total);
        report.put("totalAmount", totalAmount.toPlainString());
        report.put("countsByTransition", countsByTransition);
        report.put("amountsByTransition", amountsByTransition);
        report.put("details", details);

        return report;
    }

    private Path writeFile(Map<String, Object> report, Instant now) {
        try {
            Files.createDirectories(reportDir);
            Instant dayStart = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
            Path file = reportDir.resolve("loan-state-metrics-" + DATE.format(dayStart) + ".json");
            objectMapper.writeValue(file.toFile(), report);
            log.info("Loan state metrics dump written: {} (transitions={})", file, report.get("totalTransitions"));
            return file;
        } catch (IOException e) {
            log.error("Failed to write loan state metrics dump", e);
            throw new IllegalStateException("Failed to write loan state metrics dump", e);
        }
    }

    /** Алиасы для удобства вызова из тестов/планировщиков. */
    public Path dump() {
        return dumpDailyReport();
    }

    public Path exportReport() {
        return dumpDailyReport();
    }

    public Path run() {
        return dumpDailyReport();
    }
}
