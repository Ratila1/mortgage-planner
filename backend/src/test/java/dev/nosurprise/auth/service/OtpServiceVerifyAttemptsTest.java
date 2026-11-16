package dev.nosurprise.auth.service;

import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Защита OTP-кода от перебора (§7): 6-значный код живёт 10 минут — без счётчика
 * неудачных попыток код можно подбирать. После MAX_VERIFY_ATTEMPTS неудач код удаляется.
 */
@ExtendWith(MockitoExtension.class)
class OtpServiceVerifyAttemptsTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    UserService userService;
    @Mock
    TokenService tokenService;
    @Mock
    RefreshTokenService refreshTokenService;
    @Mock
    RabbitEventPublisher publisher;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> values;

    private OtpService serviceWithStoredCode() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(OtpService.OTP_KEY_PREFIX + EMAIL)).thenReturn(OtpService.sha256("123456"));
        return new OtpService(userService, tokenService, refreshTokenService,
                new JwtProperties("test-secret-0123456789abcdef0123456789",
                        Duration.ofMinutes(15), Duration.ofDays(30)),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), publisher, redis);
    }

    @Test
    @DisplayName("неверный код → счётчик неудач увеличивается, OtpInvalidException")
    void wrongCodeIncrementsFailCounter() {
        OtpService service = serviceWithStoredCode();
        when(redis.execute(any(RedisScript.class), eq(List.of(OtpService.OTP_FAIL_KEY_PREFIX + EMAIL)),
                eq(Long.toString(OtpService.OTP_TTL.toSeconds())))).thenReturn(1L);

        assertThatThrownBy(() -> service.verifyOtp(EMAIL, "000000", "127.0.0.1"))
                .isInstanceOf(OtpInvalidException.class);

        verify(redis).execute(any(RedisScript.class), eq(List.of(OtpService.OTP_FAIL_KEY_PREFIX + EMAIL)),
                eq(Long.toString(OtpService.OTP_TTL.toSeconds())));
        verify(redis, never()).delete(OtpService.OTP_KEY_PREFIX + EMAIL);
    }

    @Test
    @DisplayName("MAX_VERIFY_ATTEMPTS неудач подряд → код удаляется, перебор невозможен")
    void codeDeletedAfterMaxAttempts() {
        OtpService service = serviceWithStoredCode();
        when(redis.execute(any(RedisScript.class), eq(List.of(OtpService.OTP_FAIL_KEY_PREFIX + EMAIL)),
                eq(Long.toString(OtpService.OTP_TTL.toSeconds()))))
                .thenReturn((long) OtpService.MAX_VERIFY_ATTEMPTS);

        assertThatThrownBy(() -> service.verifyOtp(EMAIL, "000000", "127.0.0.1"))
                .isInstanceOf(OtpInvalidException.class);

        verify(redis).delete(OtpService.OTP_KEY_PREFIX + EMAIL);
        verify(redis).delete(OtpService.OTP_FAIL_KEY_PREFIX + EMAIL);
    }

    @Test
    @DisplayName("верный код → fail-счётчик и сам код удаляются, refresh выдаётся")
    void successResetsFailCounter() {
        OtpService service = serviceWithStoredCode();
        UUID userId = UUID.randomUUID();
        UserEntity user = mock(UserEntity.class);
        when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(userId);
        when(user.isEmailVerified()).thenReturn(true);
        when(user.getRoles()).thenReturn(java.util.Set.of(dev.nosurprise.user.Role.USER));
        when(tokenService.generate(org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn("access-token");
        when(refreshTokenService.issue(userId, "127.0.0.1"))
                .thenReturn(new IssuedRefreshToken(userId, "opaque", userId + ".opaque", Instant.EPOCH));

        var result = service.verifyOtp(EMAIL, "123456", "127.0.0.1");

        assertThat(result.accessToken()).isNotBlank();
        verify(redis).delete(OtpService.OTP_FAIL_KEY_PREFIX + EMAIL);
        verify(redis).delete(OtpService.OTP_KEY_PREFIX + EMAIL);
    }

    @Test
    @DisplayName("новый код сбрасывает счётчик неудачных попыток")
    void requestOtpResetsFailCounter() {
        when(redis.opsForValue()).thenReturn(values);
        when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(mock(UserEntity.class)));
        when(redis.execute(any(RedisScript.class), eq(List.of(OtpService.OTP_RATE_KEY_PREFIX + EMAIL)),
                eq(Long.toString(OtpService.OTP_RATE_TTL.toSeconds())))).thenReturn(1L);
        OtpService service = new OtpService(userService, tokenService, refreshTokenService,
                new JwtProperties("test-secret-0123456789abcdef0123456789",
                        Duration.ofMinutes(15), Duration.ofDays(30)),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), publisher, redis);

        service.requestOtp(EMAIL);

        verify(values).set(eq(OtpService.OTP_KEY_PREFIX + EMAIL), anyString(), eq(OtpService.OTP_TTL));
        verify(redis).delete(OtpService.OTP_FAIL_KEY_PREFIX + EMAIL);
    }
}
