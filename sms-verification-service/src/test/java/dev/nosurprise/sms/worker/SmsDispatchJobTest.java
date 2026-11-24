package dev.nosurprise.sms.worker;

import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.SmsAttemptRepository;
import dev.nosurprise.sms.domain.SmsRequest;
import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.domain.SmsStatus;
import dev.nosurprise.sms.outbox.OutboxEvent;
import dev.nosurprise.sms.outbox.OutboxEventRepository;
import dev.nosurprise.sms.provider.SmsSender;
import dev.nosurprise.sms.provider.SmsSender.SendResult;
import dev.nosurprise.sms.service.MessageFormatter;
import dev.nosurprise.sms.service.SmsCodeHandoffStore;
import dev.nosurprise.sms.telegram.service.VerificationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

class SmsDispatchJobTest {

    private static final UUID ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private final SmsRequestRepository repo = mock(SmsRequestRepository.class);
    private final SmsAttemptRepository attempts = mock(SmsAttemptRepository.class);
    private final SmsSender provider = mock(SmsSender.class);
    private final VerificationService telegram = mock(VerificationService.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final SmsCodeHandoffStore codeHandoff = mock(SmsCodeHandoffStore.class);
    private final MessageFormatter messageFormatter = mock(MessageFormatter.class);
    private final MeterRegistry metrics = new SimpleMeterRegistry();

    private final SmsProperties props = new SmsProperties(
            List.of(), 3, Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
            3, 0.7, 100, Duration.ofSeconds(30), Duration.ofDays(7), new SmsProperties.RateLimit(3));

    private SmsDispatchJob job;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        job = new SmsDispatchJob(repo, attempts, provider, telegram, outbox,
                codeHandoff, messageFormatter, new TransactionTemplate(txManager), props, metrics);

        SmsRequest request = new SmsRequest();
        request.setId(ID);
        request.setPhone("+375291234567");
        request.setLanguage(dev.nosurprise.sms.domain.Language.RU);
        request.setMessage("Код подтверждения: ******");
        when(repo.findById(ID)).thenReturn(Optional.of(request));
        when(repo.claimBatch(anyInt(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(List.of(ID));
        when(repo.renewLease(eq(ID), anyString(), anyDouble())).thenReturn(1);
        when(codeHandoff.get(ID)).thenReturn(Optional.of("123456"));
        when(messageFormatter.format(any(), anyString())).thenReturn("Код подтверждения: 123456");
    }

    @Test
    @DisplayName("успешная доставка SMS ставит Telegram-доставку в outbox")
    void successfulDeliveryTriggersTelegram() {
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.success());
        when(repo.findStatusById(ID)).thenReturn(Optional.of(SmsStatus.DELIVERED));

        job.dispatch();

        verify(outbox).insertIfPendingVerification(any(UUID.class), eq(OutboxEvent.SMS_DELIVERED), eq(ID), any());
        verify(telegram, never()).deliverBySmsId(ID);
        verify(telegram, never()).markNotDelivered(any());
    }

    @Test
    @DisplayName("промежуточная неудача оставляет Telegram-верификацию в игре")
    void retryableFailureKeepsTelegramPending() {
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.failure());
        when(repo.findStatusById(ID)).thenReturn(Optional.of(SmsStatus.CREATED));

        job.dispatch();

        verify(telegram, never()).markNotDelivered(any());
        verify(telegram, never()).deliverBySmsId(any());
    }

    @Test
    @DisplayName("финальная неудача помечает Telegram-верификацию как NOT_DELIVERED")
    void finalFailureMarksTelegramNotDelivered() {
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.failure());
        when(repo.findStatusById(ID)).thenReturn(Optional.of(SmsStatus.NOT_DELIVERED));

        job.dispatch();

        verify(telegram).markNotDelivered(ID);
        verify(telegram, never()).deliverBySmsId(any());
    }

    @Test
    @DisplayName("потерянный lease: строка не отправляется повторно")
    void lostLeaseSkipsRow() {
        when(repo.renewLease(eq(ID), anyString(), anyDouble())).thenReturn(0);

        job.dispatch();

        verify(provider, never()).send(anyString(), anyString());
        verify(telegram, never()).deliverBySmsId(any());
        verify(telegram, never()).markNotDelivered(any());
    }

    @Test
    @DisplayName("просроченные коды гасятся до захвата пачки")
    void expiresStaleBeforeClaim() {
        when(repo.claimBatch(anyInt(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(List.of());

        job.dispatch();

        verify(repo).expireStale(any());
        verify(repo).closeExhausted(anyInt());
    }

    @Test
    @DisplayName("неуспешная отправка не ставит Telegram-доставку в outbox")
    void failedSendDoesNotEnqueueOutbox() {
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.failure());
        when(repo.findStatusById(ID)).thenReturn(Optional.of(SmsStatus.CREATED));

        job.dispatch();

        verify(outbox, never()).insertIfPendingVerification(any(), anyString(), any(), any());
        verify(telegram, never()).deliverBySmsId(ID);
    }
}
