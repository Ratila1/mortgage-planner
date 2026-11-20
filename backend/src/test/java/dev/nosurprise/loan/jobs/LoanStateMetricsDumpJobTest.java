package dev.nosurprise.loan.jobs;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanEvent;
import dev.nosurprise.loan.application.LoanEventRepository;
import dev.nosurprise.loan.application.LoanEventType;
import dev.nosurprise.loan.application.LoanStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanStateMetricsDumpJobTest {

    @Mock
    private LoanApplicationRepository applications;
    @Mock
    private LoanEventRepository events;

    @TempDir
    Path tempDir;

    @Test
    void dumpsDailyReportToFile() {
        Instant fixed = Instant.parse("2026-11-20T23:00:00Z");
        Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);

        UUID appId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        LoanEvent event = mock(LoanEvent.class);
        when(event.getApplicationId()).thenReturn(appId);

        when(event.getFromStatus()).thenReturn(LoanStatus.SUBMITTED);
        when(event.getToStatus()).thenReturn(LoanStatus.APPROVED);
        when(event.getActorId()).thenReturn(userId);
        when(event.getOccurredAt()).thenReturn(fixed);

        when(events.findByTypeAndOccurredAtBetween(any(), any(), any())).thenReturn(List.of(event));

        LoanApplication application = mock(LoanApplication.class);
        when(application.getId()).thenReturn(appId);
        when(application.getUserId()).thenReturn(userId);
        when(application.getAmount()).thenReturn(new BigDecimal("500.00"));
        when(applications.findAllById(any())).thenReturn(List.of(application));

        LoanStateMetricsDumpJob job = new LoanStateMetricsDumpJob(applications, events, new ObjectMapper(), clock,
                tempDir.toString());

        Path file = job.dumpDailyReport();
        assertThat(file).exists();
        assertThat(file.getFileName().toString()).isEqualTo("loan-state-metrics-2026-11-20.json");
    }
}
