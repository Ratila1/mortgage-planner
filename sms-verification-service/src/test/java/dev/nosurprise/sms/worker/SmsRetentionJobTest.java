package dev.nosurprise.sms.worker;

import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.telegram.domain.VerificationRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsRetentionJobTest {

    private final SmsRequestRepository requests = mock(SmsRequestRepository.class);
    private final VerificationRequestRepository verifications = mock(VerificationRequestRepository.class);
    private final MeterRegistry metrics = new SimpleMeterRegistry();
    private final SmsProperties props = new SmsProperties(
            List.of(), 3, Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1),
            3, 0.7, 100, Duration.ofSeconds(30), Duration.ofDays(7), new SmsProperties.RateLimit(3));

    @Test
    @DisplayName("retention одной границей чистит sms_request и verification_requests и считает метрики")
    void cleanupUsesOneCutoffForBothTables() {
        when(requests.deleteOlderThan(any())).thenReturn(4);
        when(verifications.deleteOlderThan(any())).thenReturn(2);
        Instant before = Instant.now().minus(props.retentionRequest());

        new SmsRetentionJob(requests, verifications, props, metrics).cleanupRequests();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(requests).deleteOlderThan(cutoff.capture());
        verify(verifications).deleteOlderThan(eq(cutoff.getValue()));
        assertThat(cutoff.getValue())
                .isBetween(before.minusSeconds(5), Instant.now().minus(props.retentionRequest()).plusSeconds(5));

        assertThat(metrics.counter("sms_retention_deleted_total", "table", "sms_request").count())
                .isEqualTo(4);
        assertThat(metrics.counter("sms_retention_deleted_total", "table", "verification_requests").count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("пустая чистка не падает и пишет нули в метрики")
    void cleanupWithNothingToDelete() {
        when(requests.deleteOlderThan(any())).thenReturn(0);
        when(verifications.deleteOlderThan(any())).thenReturn(0);

        new SmsRetentionJob(requests, verifications, props, metrics).cleanupRequests();

        assertThat(metrics.counter("sms_retention_deleted_total", "table", "sms_request").count()).isZero();
        assertThat(metrics.counter("sms_retention_deleted_total", "table", "verification_requests").count()).isZero();
    }
}
