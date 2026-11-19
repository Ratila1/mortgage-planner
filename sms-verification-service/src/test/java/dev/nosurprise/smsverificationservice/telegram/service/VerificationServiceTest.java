package dev.nosurprise.smsverificationservice.telegram.service;

import dev.nosurprise.smsverificationservice.service.OtpHasher;
import dev.nosurprise.smsverificationservice.service.RedisOtpStore;
import dev.nosurprise.smsverificationservice.telegram.config.VerificationProperties;
import dev.nosurprise.smsverificationservice.telegram.domain.TelegramBinding;
import dev.nosurprise.smsverificationservice.telegram.domain.VerificationRequest;
import dev.nosurprise.smsverificationservice.telegram.domain.VerificationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerificationServiceTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SMS_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final long CHAT_ID = 292199985L;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final OtpHasher hasher = new OtpHasher();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final VerificationRequestRepository repository = mock(VerificationRequestRepository.class);
    private final BindingService bindings = mock(BindingService.class);
    private final TelegramBotService bot = mock(TelegramBotService.class);
    private final MainBackendClient backend = mock(MainBackendClient.class);
    private final TelegramEventPublisher events = mock(TelegramEventPublisher.class);
    private final RedisOtpStore smsOtps = mock(RedisOtpStore.class);

    private final VerificationRequest request = new VerificationRequest(ID, USER, "SIGNING",
            "33333333-3333-3333-3333-333333333333", "SENT", NOW, NOW.plus(Duration.ofMinutes(5)));
    private VerificationService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(repository.findById(ID)).thenReturn(Optional.of(request));
        when(repository.findByIdAndUserId(ID, USER)).thenReturn(Optional.of(request));
        service = new VerificationService(redis, repository, bindings, bot, backend, events, hasher, smsOtps,
                Clock.fixed(NOW, ZoneOffset.UTC), new VerificationProperties(Duration.ofMinutes(5), 3));
    }

    private void storeCode(String code, String userId) {
        Map<Object, Object> stored = new HashMap<>();
        stored.put("hash", hasher.hash(code));
        stored.put("userId", userId);
        when(hashes.entries("tg:otp:" + ID)).thenReturn(stored);
    }

    private TelegramBinding binding() {
        return new TelegramBinding(USER, "+375291234567", CHAT_ID, "tester", NOW);
    }

    @Test
    @DisplayName("верный код завершает операцию на бэкенде и помечает верификацию подтверждённой")
    void correctCodeCompletesBackendOperation() {
        storeCode("123456", USER.toString());

        assertThat(service.confirm(ID, USER, "123456")).isTrue();

        verify(backend).completeVerification(ID);
        verify(redis).delete("tg:otp:" + ID);
        verify(values).set("tg:otp:used:" + ID, "1", Duration.ofHours(24));
        assertThat(request.getStatus()).isEqualTo("APPROVED");
        assertThat(request.getCompletedAt()).isEqualTo(NOW);
        verify(events).verificationCompleted(ID, USER, "SIGNING");
    }

    @Test
    @DisplayName("повторно подтверждённый код не вызывает бэкенд")
    void alreadyUsedCodeIsIdempotent() {
        when(redis.hasKey("tg:otp:used:" + ID)).thenReturn(true);

        assertThat(service.confirm(ID, USER, "123456")).isTrue();

        verifyNoInteractions(backend);
        verify(hashes, never()).entries(anyString());
    }

    @Test
    @DisplayName("неверный код не завершает операцию")
    void wrongCodeDoesNotCompleteOperation() {
        storeCode("123456", USER.toString());
        when(hashes.increment("tg:otp:" + ID, "attempts", 1)).thenReturn(1L);

        assertThat(service.confirm(ID, USER, "654321")).isFalse();

        verifyNoInteractions(backend);
        verify(redis, never()).delete("tg:otp:" + ID);
    }

    @Test
    @DisplayName("третья неудачная попытка сжигает код")
    void thirdWrongAttemptBurnsCode() {
        storeCode("123456", USER.toString());
        when(hashes.increment("tg:otp:" + ID, "attempts", 1)).thenReturn(3L);

        assertThat(service.confirm(ID, USER, "654321")).isFalse();

        verify(redis).delete("tg:otp:" + ID);
        assertThat(request.getStatus()).isEqualTo("REJECTED");
        verifyNoInteractions(backend);
    }

    @Test
    @DisplayName("код другого пользователя отклоняется")
    void codeOfAnotherUserIsRejected() {
        storeCode("123456", UUID.randomUUID().toString());

        assertThat(service.confirm(ID, USER, "123456")).isFalse();

        verifyNoInteractions(backend);
        verify(hashes, never()).increment(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("статус APPROVED берётся из использованного кода")
    void statusUsesUsedKey() {
        when(redis.hasKey("tg:otp:used:" + ID)).thenReturn(true);

        assertThat(service.status(ID, USER)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("чужой пользователь не видит статус верификации")
    void foreignUserCannotReadStatus() {
        when(redis.hasKey("tg:otp:used:" + ID)).thenReturn(true);

        assertThat(service.status(ID, UUID.randomUUID())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("чужой пользователь не может подтвердить даже использованный код")
    void foreignUserCannotReplayUsedCode() {
        when(redis.hasKey("tg:otp:used:" + ID)).thenReturn(true);

        assertThat(service.confirm(ID, UUID.randomUUID(), "123456")).isFalse();

        verifyNoInteractions(backend);
    }

    @Test
    @DisplayName("статус берётся из БД, а не из наличия Redis-ключа")
    void statusUsesPersistedState() {
        when(redis.hasKey("tg:otp:" + ID)).thenReturn(true);

        assertThat(service.status(ID, USER)).isEqualTo("SENT");

        request.setStatus("REJECTED");
        assertThat(service.status(ID, USER)).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("без привязки код не пишется в Redis и не отправляется")
    void noBindingLeavesNoOtp() {
        when(repository.existsById(ID)).thenReturn(false);
        when(bindings.findByUser(USER)).thenReturn(Optional.empty());

        service.handleRequested(ID, USER, "SIGNING", "33333333-3333-3333-3333-333333333333", SMS_ID, 5);

        verify(redis, never()).expire(anyString(), any(Duration.class));
        verify(bot, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    @DisplayName("в Telegram уходит тот же код, что был сгенерирован для SMS")
    void telegramReceivesSmsCode() {
        request.setStatus("PENDING");
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));
        when(smsOtps.getPlainCode(SMS_ID)).thenReturn(Optional.of("123456"));

        service.handleRequested(ID, USER, "SIGNING", "33333333-3333-3333-3333-333333333333", SMS_ID, 5);

        verify(bot).sendMessage(CHAT_ID, "Ваш код подтверждения: 123456");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> stored = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(eq("tg:otp:" + ID), stored.capture());
        assertThat(stored.getValue().get("hash")).isEqualTo(hasher.hash("123456"));
        verify(redis).expire("tg:otp:" + ID, Duration.ofMinutes(5));
        assertThat(request.getStatus()).isEqualTo("SENT");
        verify(events).verificationSent(ID, USER, "SIGNING");
    }

    @Test
    @DisplayName("событие без smsId оставляет верификацию PENDING и не отправляет чужой код")
    void eventWithoutSmsIdLeavesVerificationPending() {
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));

        service.handleRequested(ID, USER, "SIGNING", "33333333-3333-3333-3333-333333333333", null, 5);

        verify(smsOtps, never()).getPlainCode(any());
        verify(bot, never()).sendMessage(anyLong(), anyString());
        verify(hashes, never()).putAll(anyString(), anyMap());
        verifyNoInteractions(events);
        assertSavedStatusIs("PENDING");
    }

    @Test
    @DisplayName("истёкший SMS-код оставляет верификацию PENDING и не отправляется в Telegram")
    void expiredSmsCodeLeavesVerificationPending() {
        when(bindings.findByUser(USER)).thenReturn(Optional.of(binding()));
        when(smsOtps.getPlainCode(SMS_ID)).thenReturn(Optional.empty());

        service.handleRequested(ID, USER, "SIGNING", "33333333-3333-3333-3333-333333333333", SMS_ID, 5);

        verify(smsOtps).getPlainCode(SMS_ID);
        verify(bot, never()).sendMessage(anyLong(), anyString());
        verify(hashes, never()).putAll(anyString(), anyMap());
        verifyNoInteractions(events);
        assertSavedStatusIs("PENDING");
    }

    private void assertSavedStatusIs(String status) {
        ArgumentCaptor<VerificationRequest> saved = ArgumentCaptor.forClass(VerificationRequest.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(status);
    }
}
