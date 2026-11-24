package dev.nosurprise.sms.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtpChallengeServiceTest {

    private static final String CHALLENGE_KEY = "sms:otp:11111111-1111-1111-1111-111111111111";
    private static final String USED_KEY = "sms:otp:used:11111111-1111-1111-1111-111111111111";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
    private final OtpHasher hasher = mock(OtpHasher.class);

    private OtpChallengeService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForHash()).thenReturn(hashOps);
        service = new OtpChallengeService(redis, hasher);
    }

    @Test
    @DisplayName("check: used-маркер возвращает VERIFIED без проверки кода")
    void checkReturnsVerifiedWhenUsed() {
        when(redis.hasKey(USED_KEY)).thenReturn(true);

        OtpChallengeService.Outcome outcome = service.check(CHALLENGE_KEY, USED_KEY, "123456", 3);

        assertThat(outcome).isEqualTo(OtpChallengeService.Outcome.VERIFIED);
        verify(hashOps, never()).get(anyString(), anyString());
        verify(hasher, never()).verify(anyString(), anyString());
    }

    @Test
    @DisplayName("check: без used-маркера делегирует verify и возвращает его исход")
    void checkDelegatesToVerifyWhenNotUsed() {
        when(redis.hasKey(USED_KEY)).thenReturn(false);
        when(hashOps.get(CHALLENGE_KEY, "hash")).thenReturn(null);

        OtpChallengeService.Outcome outcome = service.check(CHALLENGE_KEY, USED_KEY, "123456", 3);

        assertThat(outcome).isEqualTo(OtpChallengeService.Outcome.MISSING);
        verify(hashOps).get(CHALLENGE_KEY, "hash");
    }

    @Test
    @DisplayName("check: без used-маркера возвращает VERIFIED при верном коде")
    void checkDelegatesVerifiedOutcome() {
        when(redis.hasKey(USED_KEY)).thenReturn(false);
        when(hashOps.get(CHALLENGE_KEY, "hash")).thenReturn("stored-hash");
        when(hasher.verify("123456", "stored-hash")).thenReturn(true);

        OtpChallengeService.Outcome outcome = service.check(CHALLENGE_KEY, USED_KEY, "123456", 3);

        assertThat(outcome).isEqualTo(OtpChallengeService.Outcome.VERIFIED);
        verify(hasher).verify("123456", "stored-hash");
    }
}
