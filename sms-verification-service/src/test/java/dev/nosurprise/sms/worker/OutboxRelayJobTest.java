package dev.nosurprise.sms.worker;

import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.outbox.OutboxEvent;
import dev.nosurprise.sms.outbox.OutboxEventRepository;
import dev.nosurprise.sms.outbox.OutboxStatus;
import dev.nosurprise.sms.telegram.service.VerificationService;
import dev.nosurprise.sms.telegram.service.VerificationService.DeliveryOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayJobTest {

    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SMS_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final VerificationService telegram = mock(VerificationService.class);
    private final SmsProperties props = new SmsProperties(
            List.of(), 3, Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
            3, 0.7, 100, Duration.ofSeconds(30), Duration.ofDays(7), new SmsProperties.RateLimit(3));
    private final OutboxRelayJob job = new OutboxRelayJob(outbox, telegram, props, new SimpleMeterRegistry());

    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        event = mock(OutboxEvent.class);
        when(event.getId()).thenReturn(EVENT_ID);
        when(event.getSmsId()).thenReturn(SMS_ID);
        when(event.getEventType()).thenReturn(OutboxEvent.SMS_DELIVERED);
        when(event.getStatus()).thenReturn(OutboxStatus.NEW);
        when(outbox.claimBatch(anyInt(), anyDouble(), anyString())).thenReturn(List.of(EVENT_ID));
        when(outbox.findById(EVENT_ID)).thenReturn(java.util.Optional.of(event));
    }

    @Test
    @DisplayName("DELIVERED outcome marks outbox processed")
    void deliveredMarksProcessed() {
        when(telegram.deliverBySmsId(SMS_ID)).thenReturn(DeliveryOutcome.DELIVERED);

        job.relay();

        verify(outbox).markProcessed(eq(EVENT_ID), any(Instant.class));
        verify(outbox, never()).markRetryOrDead(any(), anyInt(), anyDouble(), anyString(), any());
        verify(outbox, never()).markDead(any(), anyString(), any());
    }

    @Test
    @DisplayName("NO_OP outcome marks outbox processed")
    void noOpMarksProcessed() {
        when(telegram.deliverBySmsId(SMS_ID)).thenReturn(DeliveryOutcome.NO_OP);

        job.relay();

        verify(outbox).markProcessed(eq(EVENT_ID), any(Instant.class));
    }

    @Test
    @DisplayName("RETRYABLE outcome schedules retry or dead-letter")
    void retryableSchedulesRetry() {
        when(telegram.deliverBySmsId(SMS_ID)).thenReturn(DeliveryOutcome.RETRYABLE);

        job.relay();

        verify(outbox).markRetryOrDead(eq(EVENT_ID), eq(10), eq(30.0), eq("RETRYABLE"), any(Instant.class));
    }

    @Test
    @DisplayName("PERMANENT outcome dead-letters event")
    void permanentMarksDead() {
        when(telegram.deliverBySmsId(SMS_ID)).thenReturn(DeliveryOutcome.PERMANENT);

        job.relay();

        verify(outbox).markDead(eq(EVENT_ID), eq("PERMANENT"), any(Instant.class));
    }

    @Test
    @DisplayName("exception from delivery is treated as retryable")
    void exceptionIsRetryable() {
        when(telegram.deliverBySmsId(SMS_ID)).thenThrow(new RuntimeException("boom"));

        job.relay();

        verify(outbox).markRetryOrDead(eq(EVENT_ID), eq(10), eq(30.0), eq("RuntimeException"), any(Instant.class));
    }

    @Test
    @DisplayName("relay reconciles missing delivered outbox rows")
    void reconcilesMissingRows() {
        when(outbox.claimBatch(anyInt(), anyDouble(), anyString())).thenReturn(List.of());

        job.relay();

        verify(outbox).reconcileMissingDelivered(eq(OutboxEvent.SMS_DELIVERED), any(Instant.class));
    }
}
