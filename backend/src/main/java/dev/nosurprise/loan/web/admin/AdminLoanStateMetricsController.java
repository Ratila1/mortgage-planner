package dev.nosurprise.loan.web.admin;

import dev.nosurprise.loan.jobs.LoanStateMetricsDumpJob;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ручной запуск дампа метрик переходов состояний (для админа и бэкенда) — позволяет
 * получить отчёт раньше ежедневного запуска в 23:00.
 */
@RestController
@RequestMapping("/api/admin/loan-state-metrics")
public class AdminLoanStateMetricsController {

    private final LoanStateMetricsDumpJob job;

    public AdminLoanStateMetricsController(LoanStateMetricsDumpJob job) {
        this.job = job;
    }

    @GetMapping("/report")
    public Map<String, Object> report() {
        return job.buildReport();
    }

    @PostMapping("/dump")
    public Map<String, Object> dump() {
        java.nio.file.Path file = job.dumpDailyReport();
        return Map.of("file", file.toString(), "report", job.buildReport());
    }
}
