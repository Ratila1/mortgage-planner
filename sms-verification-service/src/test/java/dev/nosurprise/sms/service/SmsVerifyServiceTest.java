package dev.nosurprise.sms.service;

import dev.nosurprise.sms.api.error.NotFoundException;
import dev.nosurprise.sms.config.SmsProperties;
import dev.nosurprise.sms.domain.SmsRequest;
import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.domain.SmsStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsVerifyServiceTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String KEY = OtpChallengeService.smsKey(ID);
    private static final String USED_KEY = OtpChallengeService.smsUsedKey(ID);

    private final SmsRequestRepository repo = mock(SmsRequestRepository.class);
    private final OtpChallengeService challenges = mock(OtpChallengeService.class);
    private final SmsProperties props = new SmsProperties(
            List.of(), 3, Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1),
            3, 0.7, 100, Duration.ofSeconds(30), Duration.ofDays(7), new SmsProperties.RateLimit(3));
    private final MeterRegistry metrics = new SimpleMeterRegistry();

    private SmsVerifyService service;

    @BeforeEach
    void setUp() {
        service = new SmsVerifyService(repo, challenges, props, metrics);
    }

    private SmsRequest smsRequest(SmsStatus status) {
        SmsRequest req = new SmsRequest();
        req.setId(ID);
        req.setStatus(status);
        return req;
    }

    @Test
    @DisplayName("сожжённый код (EXPIRED) отдаёт EXPIRED, а не NOT_READY")
    void burnedCodeIsReportedAsExpired() {
        when(repo.findById(ID)).thenReturn(Optional.of(smsRequest(SmsStatus.EXPIRED)));
        when(challenges.isUsed(USED_KEY)).thenReturn(false);

        var result = service.verify(ID, "123456");

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo("EXPIRED");
        verify(challenges, never()).verify(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("не доставленный (CREATED) код отдаёт NOT_READY")
    void undeliveredCodeIsNotReady() {
        when(repo.findById(ID)).thenReturn(Optional.of(smsRequest(SmsStatus.CREATED)));
        when(challenges.isUsed(USED_KEY)).thenReturn(false);

        var result = service.verify(ID, "123456");

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo("NOT_READY");
    }

    @Test
    @DisplayName("нет OTP в Redis (истёк или сожжён) → EXPIRED")
    void missingOtpIsReportedAsExpired() {
        when(repo.findById(ID)).thenReturn(Optional.of(smsRequest(SmsStatus.DELIVERED)));
        when(challenges.isUsed(USED_KEY)).thenReturn(false);
        when(challenges.check(KEY, USED_KEY, "123456", 3)).thenReturn(OtpChallengeService.Outcome.MISSING);

        var result = service.verify(ID, "123456");

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo("EXPIRED");
        verify(repo).markStatus(eq(ID), eq(SmsStatus.EXPIRED), any());
    }

    @Test
    @DisplayName("верный код после DELIVERED → SUCCESS и помечает VERIFIED")
    void correctCodeMarksVerified() {
        when(repo.findById(ID)).thenReturn(Optional.of(smsRequest(SmsStatus.DELIVERED)));
        when(challenges.isUsed(USED_KEY)).thenReturn(false);
        when(challenges.check(KEY, USED_KEY, "123456", 3)).thenReturn(OtpChallengeService.Outcome.VERIFIED);

        var result = service.verify(ID, "123456");

        assertThat(result.verified()).isTrue();
        verify(repo).markStatus(eq(ID), eq(SmsStatus.VERIFIED), any());
        verify(challenges).markUsed(USED_KEY, Duration.ofHours(24));
    }

    @Test
    @DisplayName("неверный код → INVALID_CODE и код не сжигается")
    void invalidCodeIsRejected() {
        when(repo.findById(ID)).thenReturn(Optional.of(smsRequest(SmsStatus.DELIVERED)));
        when(challenges.check(KEY, USED_KEY, "654321", 3)).thenReturn(OtpChallengeService.Outcome.INVALID);

        var result = service.verify(ID, "654321");

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).isEqualTo("INVALID_CODE");
        verify(challenges, never()).markUsed(anyString(), any());
    }

    @Test
    @DisplayName("исчерпание попыток сжигает код и отдаёт TOO_MANY_ATTEMPTS")
    void exhaustedAttemptsBurnCode() {
        when(repo.findById(ID)).thenReturn(Optional.of(smsRequest(SmsStatus.DELIVERED)));
        when(challenges.check(KEY, USED_KEY, "654321", 3)).thenReturn(OtpChallengeService.Outcome.TOO_MANY_ATTEMPTS);

        var result = service.verify(ID, "654321");

        assertThat(result.reason()).isEqualTo("TOO_MANY_ATTEMPTS");
        verify(repo).markStatus(eq(ID), eq(SmsStatus.EXPIRED), any());
    }

    @Test
    @DisplayName("неизвестный id → NotFoundException")
    void unknownIdThrows() {
        when(repo.findById(ID)).thenReturn(Optional.empty());
        when(challenges.isUsed(USED_KEY)).thenReturn(false);

        try {
            service.verify(ID, "123456");
            throw new AssertionError("expected NotFoundException");
        } catch (NotFoundException expected) {
        }
    }

    @Test
    @DisplayName("used-маркер даёт идемпотентный успех без доменной проверки и verify")
    void usedMarkerShortCircuits() {
        when(challenges.isUsed(USED_KEY)).thenReturn(true);

        var result = service.verify(ID, "123456");

        assertThat(result.verified()).isTrue();
        verify(repo, never()).findById(any());
        verify(challenges, never()).verify(anyString(), anyString(), anyInt());
        verify(challenges, never()).check(anyString(), anyString(), anyString(), anyInt());
        verify(repo, never()).markStatus(any(), any(), any());
    }
}
