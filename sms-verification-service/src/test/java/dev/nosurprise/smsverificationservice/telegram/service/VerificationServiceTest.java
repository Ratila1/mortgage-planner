package dev.nosurprise.smsverificationservice.telegram.service;

import dev.nosurprise.smsverificationservice.service.OtpHasher;
import dev.nosurprise.smsverificationservice.telegram.config.VerificationProperties;
import dev.nosurprise.smsverificationservice.telegram.domain.VerificationRequest;
import dev.nosurprise.smsverificationservice.telegram.domain.VerificationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerificationServiceTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
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

    private final VerificationRequest request = new VerificationRequest(ID, USER, "SIGNING",
            "33333333-3333-3333-3333-333333333333", "SENT", NOW, NOW.plus(Duration.ofMinutes(5)));
    private VerificationService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(repository.findById(ID)).thenReturn(Optional.of(request));
        when(repository.findByIdAndUserId(ID, USER)).thenReturn(Optional.of(request));
        service = new VerificationService(redis, repository, bindings, bot, backend, events, hasher,
                Clock.fixed(NOW, ZoneOffset.UTC), new VerificationProperties(Duration.ofMinutes(5), 3));
    }

    private void storeCode(String code, String userId) {
        Map<Object, Object> stored = new HashMap<>();
        stored.put("hash", hasher.hash(code));
        stored.put("userId", userId);
        when(hashes.entries("tg:otp:" + ID)).thenReturn(stored);
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

        service.handleRequested(ID, USER, "SIGNING", "33333333-3333-3333-3333-333333333333", 5);

        verify(redis, never()).expire(anyString(), any(Duration.class));
        verify(bot, never()).sendMessage(anyLong(), anyString());
    }
}
