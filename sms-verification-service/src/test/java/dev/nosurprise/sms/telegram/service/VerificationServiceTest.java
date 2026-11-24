package dev.nosurprise.sms.telegram.service;

import dev.nosurprise.sms.api.error.NotFoundException;
import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.SmsRequest;
import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.domain.SmsStatus;
import dev.nosurprise.sms.service.OtpChallengeService;
import dev.nosurprise.sms.outbox.OutboxEvent;
import dev.nosurprise.sms.outbox.OutboxEventRepository;
import dev.nosurprise.sms.service.SmsCodeHandoffStore;
import dev.nosurprise.sms.telegram.domain.TelegramBinding;
import dev.nosurprise.sms.telegram.domain.VerificationRequest;
import dev.nosurprise.sms.telegram.domain.VerificationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerificationServiceTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SMS_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final long CHAT_ID = 292199985L;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String OPERATION_REF = "33333333-3333-3333-3333-333333333333";
    private static final String OTP_KEY = OtpChallengeService.telegramKey(ID);
    private static final String USED_KEY = OtpChallengeService.telegramUsedKey(ID);
    private static final String DELIVERED_KEY = "tg:otp:delivered:" + ID;
    private static final SmsProperties SMS_PROPS = new SmsProperties(
            List.of(), 3, Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
            3, 0.7, 100, Duration.ofSeconds(30), Duration.ofDays(7), new SmsProperties.RateLimit(3));

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final VerificationRequestRepository repository = mock(VerificationRequestRepository.class);
    private final BindingService bindings = mock(BindingService.class);
    private final TelegramBotService bot = mock(TelegramBotService.class);
    private final MainBackendClient backend = mock(MainBackendClient.class);
    private final TelegramEventPublisher events = mock(TelegramEventPublisher.class);
    private final OtpChallengeService challenges = mock(OtpChallengeService.class);
    private final SmsCodeHandoffStore smsCodes = mock(SmsCodeHandoffStore.class);
    private final SmsRequestRepository smsRequests = mock(SmsRequestRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);

    private final VerificationRequest request = new VerificationRequest(ID, USER, "SIGNING",
            OPERATION_REF, SmsStatus.CREATED, NOW, NOW.plus(Duration.ofMinutes(5)), SMS_ID);
    private VerificationService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(repository.findById(ID)).thenReturn(Optional.of(request));
        when(repository.findByIdAndUserId(ID, USER)).thenReturn(Optional.of(request));
        when(repository.findBySmsIdAndStatus(SMS_ID, SmsStatus.CREATED)).thenReturn(Optional.of(request));
        service = new VerificationService(redis, repository, bindings, bot, backend, events, challenges, smsCodes,
                smsRequests, outbox, Clock.fixed(NOW, ZoneOffset.UTC), SMS_PROPS);
    }

    private SmsRequest sms(SmsStatus status) {
        SmsRequest sms = new SmsRequest();
        sms.setId(SMS_ID);
        sms.setStatus(status);
        return sms;
    }

    private SmsRequest deliveredSms() {
        SmsRequest sms = sms(SmsStatus.DELIVERED);
        sms.setSentAt(NOW);
        return sms;
    }

    private void pendingVerification() {
        request.setStatus(SmsStatus.CREATED);
        when(repository.findBySmsIdAndStatus(SMS_ID, SmsStatus.CREATED)).thenReturn(Optional.of(request));
    }

    private void storeCode(String userId) {
        when(challenges.attributes(OTP_KEY)).thenReturn(Optional.of(
                Map.of("userId", userId, "operationRef", OPERATION_REF)));
    }

    private TelegramBinding binding() {
        return new TelegramBinding(USER, "+375291234567", CHAT_ID, "tester", NOW);
    }

    @Test
    @DisplayName("верный код завершает операцию на бэкенде и помечает верификацию подтверждённой")
    void correctCodeCompletesBackendOperation() {
        storeCode(USER.toString());
        when(challenges.check(OTP_KEY, USED_KEY, "123456", 3)).thenReturn(OtpChallengeService.Outcome.VERIFIED);

        assertThat(service.confirm(ID, USER, "123456")).isTrue();

        verify(backend).completeVerification(ID);
        verify(challenges).markUsed(USED_KEY, Duration.ofHours(24));
        assertThat(request.getStatus()).isEqualTo(SmsStatus.VERIFIED);
        assertThat(request.getCompletedAt()).isEqualTo(NOW);
        verify(events).verificationCompleted(ID, USER, "SIGNING");
    }

    @Test
    @DisplayName("повторно подтверждённый код не вызывает бэкенд")
    void alreadyUsedCodeIsIdempotent() {
        when(challenges.isUsed(USED_KEY)).thenReturn(true);

        assertThat(service.confirm(ID, USER, "123456")).isTrue();

        verifyNoInteractions(backend);
        verify(challenges, never()).attributes(anyString());
    }

    @Test
    @DisplayName("неверный код не завершает операцию")
    void wrongCodeDoesNotCompleteOperation() {
        storeCode(USER.toString());
        when(challenges.check(OTP_KEY, USED_KEY, "654321", 3)).thenReturn(OtpChallengeService.Outcome.INVALID);

        assertThat(service.confirm(ID, USER, "654321")).isFalse();

        verifyNoInteractions(backend);
        verify(challenges, never()).markUsed(anyString(), any());
    }

    @Test
    @DisplayName("третья неудачная попытка сжигает код")
    void thirdWrongAttemptBurnsCode() {
        storeCode(USER.toString());
        when(challenges.check(OTP_KEY, USED_KEY, "654321", 3)).thenReturn(OtpChallengeService.Outcome.TOO_MANY_ATTEMPTS);

        assertThat(service.confirm(ID, USER, "654321")).isFalse();

        assertThat(request.getStatus()).isEqualTo(SmsStatus.EXPIRED);
        verifyNoInteractions(backend);
    }

    @Test
    @DisplayName("код другого пользователя отклоняется")
    void codeOfAnotherUserIsRejected() {
        storeCode(UUID.randomUUID().toString());

        assertThat(service.confirm(ID, USER, "123456")).isFalse();

        verifyNoInteractions(backend);
        verify(challenges, never()).verify(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("статус VERIFIED берётся из использованного кода")
    void statusUsesUsedKey() {
        when(challenges.isUsed(USED_KEY)).thenReturn(true);

        assertThat(service.status(ID, USER)).isEqualTo(SmsStatus.VERIFIED);
    }

    @Test
    @DisplayName("чужой пользователь получает 404, а не статус чужой верификации")
    void foreignUserCannotReadStatus() {
        assertThatThrownBy(() -> service.status(ID, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("чужой пользователь не может подтвердить даже использованный код")
    void foreignUserCannotReplayUsedCode() {
        when(challenges.isUsed(USED_KEY)).thenReturn(true);

        assertThat(service.confirm(ID, UUID.randomUUID(), "123456")).isFalse();

        verifyNoInteractions(backend);
    }

    @Test
    @DisplayName("статус берётся из БД, а не из наличия Redis-ключа")
    void statusUsesPersistedState() {
        when(challenges.isUsed(USED_KEY)).thenReturn(false);
        assertThat(service.status(ID, USER)).isEqualTo(SmsStatus.CREATED);

        request.setStatus(SmsStatus.EXPIRED);
        assertThat(service.status(ID, USER)).isEqualTo(SmsStatus.EXPIRED);
    }

    @Test
    @DisplayName("обработка события не отправляет код, пока SMS не доставлена")
    void pendingSmsDoesNotSendTelegramCode() {
        pendingVerification();
        when(smsRequests.findById(SMS_ID)).thenReturn(Optional.of(sms(SmsStatus.CREATED)));
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));

        service.handleRequested(ID, USER, "SIGNING", OPERATION_REF, SMS_ID, 5);

        verify(outbox, never()).insertIfPendingVerification(any(), anyString(), any(), any());
        verify(bot, never()).sendMessage(anyLong(), anyString());
        verify(challenges, never()).store(anyString(), anyString(), anyMap(), any());
        verifyNoInteractions(events);
        assertThat(request.getStatus()).isEqualTo(SmsStatus.CREATED);
    }

    @Test
    @DisplayName("код уходит в Telegram после успешной доставки SMS")
    void telegramReceivesSmsCodeAfterSuccessfulDelivery() {
        pendingVerification();
        when(smsRequests.findById(SMS_ID)).thenReturn(Optional.of(deliveredSms()));
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));
        when(smsCodes.get(SMS_ID)).thenReturn(Optional.of("123456"));

        service.handleRequested(ID, USER, "SIGNING", OPERATION_REF, SMS_ID, 5);
        verify(outbox).insertIfPendingVerification(any(UUID.class), eq(OutboxEvent.SMS_DELIVERED), eq(SMS_ID), any());

        service.deliverBySmsId(SMS_ID);

        verify(bot).sendMessage(CHAT_ID, "Ваш код подтверждения: 123456");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
        verify(challenges).store(eq(OTP_KEY), eq("123456"), attributes.capture(), eq(Duration.ofMinutes(5)));
        assertThat(attributes.getValue())
                .containsEntry("userId", USER.toString())
                .containsEntry("operationRef", OPERATION_REF);
        assertThat(request.getStatus()).isEqualTo(SmsStatus.DELIVERED);
        verify(events).verificationSent(ID, USER, "SIGNING");
    }

    @Test
    @DisplayName("если доставка случилась раньше события, код отправляется один раз")
    void deliveryBeforeEventSendsOnce() {
        pendingVerification();
        when(smsRequests.findById(SMS_ID)).thenReturn(Optional.of(deliveredSms()));
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));
        when(smsCodes.get(SMS_ID)).thenReturn(Optional.of("123456"));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);

        service.handleRequested(ID, USER, "SIGNING", OPERATION_REF, SMS_ID, 5);
        service.deliverBySmsId(SMS_ID);

        verify(bot, times(1)).sendMessage(CHAT_ID, "Ваш код подтверждения: 123456");
        verify(challenges, times(1)).store(eq(OTP_KEY), eq("123456"), anyMap(), any(Duration.class));
        verify(events, times(1)).verificationSent(ID, USER, "SIGNING");
    }

    @Test
    @DisplayName("повторный вызов доставки не отправляет код второй раз")
    void repeatedDeliveryIsIdempotent() {
        pendingVerification();
        when(smsRequests.findById(SMS_ID)).thenReturn(Optional.of(deliveredSms()));
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));
        when(smsCodes.get(SMS_ID)).thenReturn(Optional.of("123456"));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);

        service.deliverBySmsId(SMS_ID);
        service.deliverBySmsId(SMS_ID);

        verify(bot, times(1)).sendMessage(CHAT_ID, "Ваш код подтверждения: 123456");
    }

    @Test
    @DisplayName("без привязки код не пишется в Redis и не отправляется")
    void noBindingLeavesNoOtp() {
        pendingVerification();
        when(smsRequests.findById(SMS_ID)).thenReturn(Optional.of(deliveredSms()));
        when(bindings.findByUser(USER)).thenReturn(Optional.empty());

        service.handleRequested(ID, USER, "SIGNING", OPERATION_REF, SMS_ID, 5);

        verify(outbox).insertIfPendingVerification(any(UUID.class), eq(OutboxEvent.SMS_DELIVERED), eq(SMS_ID), any());
        verify(challenges, never()).store(anyString(), anyString(), anyMap(), any());
        verify(bot, never()).sendMessage(anyLong(), anyString());
        assertThat(request.getStatus()).isEqualTo(SmsStatus.CREATED);
    }

    @Test
    @DisplayName("событие без smsId оставляет верификацию CREATED и не отправляет чужой код")
    void eventWithoutSmsIdLeavesVerificationPending() {
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));

        service.handleRequested(ID, USER, "SIGNING", OPERATION_REF, null, 5);

        verify(outbox, never()).insertIfPendingVerification(any(), anyString(), any(), any());
        verify(smsCodes, never()).get(any());
        verify(bot, never()).sendMessage(anyLong(), anyString());
        verify(challenges, never()).store(anyString(), anyString(), anyMap(), any());
        verifyNoInteractions(events);
        assertSavedStatusIs(SmsStatus.CREATED);
    }

    @Test
    @DisplayName("истёкший SMS-код оставляет верификацию CREATED и не отправляется в Telegram")
    void expiredSmsCodeLeavesVerificationPending() {
        pendingVerification();
        when(smsRequests.findById(SMS_ID)).thenReturn(Optional.of(deliveredSms()));
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));
        when(smsCodes.get(SMS_ID)).thenReturn(Optional.empty());

        service.deliverBySmsId(SMS_ID);

        verify(smsCodes).get(SMS_ID);
        verify(bot, never()).sendMessage(anyLong(), anyString());
        verify(challenges, never()).store(anyString(), anyString(), anyMap(), any());
        verifyNoInteractions(events);
        assertThat(request.getStatus()).isEqualTo(SmsStatus.CREATED);
    }

    @Test
    @DisplayName("повтор события после сбоя отправки возвращает smsId — доставка не теряется")
    void replayAfterFailedDeliveryRetriesTelegram() {
        when(repository.existsById(ID)).thenReturn(true);
        request.setStatus(SmsStatus.CREATED);
        when(smsRequests.findById(SMS_ID)).thenReturn(Optional.of(deliveredSms()));
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));
        when(smsCodes.get(SMS_ID)).thenReturn(Optional.of("123456"));

        service.handleRequested(ID, USER, "SIGNING", OPERATION_REF, SMS_ID, 5);
        verify(outbox).insertIfPendingVerification(any(UUID.class), eq(OutboxEvent.SMS_DELIVERED), eq(SMS_ID), any());

        service.deliverBySmsId(SMS_ID);

        verify(bot).sendMessage(CHAT_ID, "Ваш код подтверждения: 123456");
        assertThat(request.getStatus()).isEqualTo(SmsStatus.DELIVERED);
    }

    @Test
    @DisplayName("повтор события по доставленной верификации ничего не переотправляет")
    void replayOfDeliveredVerificationIsNoop() {
        when(repository.existsById(ID)).thenReturn(true);
        request.setStatus(SmsStatus.DELIVERED);

        service.handleRequested(ID, USER, "SIGNING", OPERATION_REF, SMS_ID, 5);

        verify(outbox, never()).insertIfPendingVerification(any(), anyString(), any(), any());
        verifyNoInteractions(bot, smsCodes);
    }

    @Test
    @DisplayName("неудачная доставка SMS помечает telegram-верификацию NOT_DELIVERED")
    void failedDeliveryMarksTelegramVerificationNotDelivered() {
        pendingVerification();

        service.markNotDelivered(SMS_ID);

        verify(repository).findBySmsIdAndStatus(SMS_ID, SmsStatus.CREATED);
        verify(repository).save(request);
        assertThat(request.getStatus()).isEqualTo(SmsStatus.NOT_DELIVERED);
    }

    @Test
    @DisplayName("claim уже есть, но статус не сохранён: reconcile без повторной Telegram-отправки")
    void reconcileClaimedDeliveryWithoutResending() {
        pendingVerification();
        when(smsRequests.findById(SMS_ID)).thenReturn(Optional.of(deliveredSms()));
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));
        when(smsCodes.get(SMS_ID)).thenReturn(Optional.of("123456"));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(challenges.attributes(OTP_KEY)).thenReturn(Optional.of(Map.of("userId", USER.toString())));

        VerificationService.DeliveryOutcome outcome = service.deliverBySmsId(SMS_ID);

        assertThat(outcome).isEqualTo(VerificationService.DeliveryOutcome.DELIVERED);
        verify(bot, never()).sendMessage(anyLong(), anyString());
        verify(repository).save(request);
        assertThat(request.getStatus()).isEqualTo(SmsStatus.DELIVERED);
        verify(events).verificationSent(ID, USER, "SIGNING");
    }

    @Test
    @DisplayName("claim есть, challenge нет, статус CREATED: retryable, не silent ack")
    void claimedWithoutChallengeIsRetryable() {
        pendingVerification();
        when(smsRequests.findById(SMS_ID)).thenReturn(Optional.of(deliveredSms()));
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));
        when(smsCodes.get(SMS_ID)).thenReturn(Optional.of("123456"));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(challenges.attributes(OTP_KEY)).thenReturn(Optional.empty());

        VerificationService.DeliveryOutcome outcome = service.deliverBySmsId(SMS_ID);

        assertThat(outcome).isEqualTo(VerificationService.DeliveryOutcome.RETRYABLE);
        verify(bot, never()).sendMessage(anyLong(), anyString());
        verify(repository, never()).save(any());
    }

    private void assertSavedStatusIs(SmsStatus status) {
        ArgumentCaptor<VerificationRequest> saved = ArgumentCaptor.forClass(VerificationRequest.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(status);
    }
}
