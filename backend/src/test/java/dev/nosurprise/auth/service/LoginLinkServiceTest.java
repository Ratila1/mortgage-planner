package dev.nosurprise.auth.service;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Вход по ссылке из письма: токен хранится хэшем (loginlink:{sha256} → userId),
 * письмо содержит ссылку с токеном, consume одноразовый и выдаёт сессию.
 */
@ExtendWith(MockitoExtension.class)
class LoginLinkServiceTest {

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

    private LoginLinkService service() {
        return new LoginLinkService(userService, tokenService, refreshTokenService,
                new JwtProperties("test-secret-0123456789abcdef0123456789",
                        Duration.ofMinutes(15), Duration.ofDays(30)),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), publisher, redis,
                "http://localhost:8080");
    }

    private UserEntity user(UUID userId, boolean verified) {
        UserEntity user = mock(UserEntity.class);
        // getId() и getRoles() используются только в успешном consume; в остальных
        // путях они не вызываются — lenient, чтобы strict-stubs не ругался.
        lenient().when(user.getId()).thenReturn(userId);
        when(user.isEmailVerified()).thenReturn(verified);
        lenient().when(user.getRoles()).thenReturn(java.util.Set.of(dev.nosurprise.user.Role.USER));
        return user;
    }

    private void allowRateLimit() {
        when(redis.execute(any(RedisScript.class), eq(List.of(LoginLinkService.LINK_RATE_KEY_PREFIX + EMAIL)),
                eq(Long.toString(LoginLinkService.RATE_TTL.toSeconds())))).thenReturn(1L);
    }

    @Test
    @DisplayName("requestLoginLink: сохраняет хэш токена в Redis и шлёт письмо со ссылкой")
    void requestStoresHashedTokenAndPublishesEmail() {
        LoginLinkService service = service();
        when(redis.opsForValue()).thenReturn(values);
        UUID userId = UUID.randomUUID();
        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(userId);
        when(userService.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        allowRateLimit();

        service.requestLoginLink(EMAIL);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), eq(userId.toString()), eq(LoginLinkService.LINK_TTL));
        assertThat(key.getValue()).startsWith(LoginLinkService.LINK_KEY_PREFIX).hasSize(
                LoginLinkService.LINK_KEY_PREFIX.length() + 64);

        ArgumentCaptor<MailRequested> mail = ArgumentCaptor.forClass(MailRequested.class);
        verify(publisher).publish(eq("mail.requested"), mail.capture());
        assertThat(mail.getValue().subject()).isEqualTo("Ссылка для входа");
        assertThat(mail.getValue().to()).isEqualTo(EMAIL);
        assertThat(mail.getValue().body()).contains("http://localhost:8080/login?token=");
    }

    @Test
    @DisplayName("requestLoginLink: неизвестный email → письмо не уходит (существование не раскрывается)")
    void unknownEmailDoesNotPublish() {
        LoginLinkService service = service();
        when(userService.findByEmail(EMAIL)).thenReturn(Optional.empty());
        allowRateLimit();

        service.requestLoginLink(EMAIL);

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(publisher, never()).publish(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("requestLoginLink: превышен лимит → OtpRateLimitExceededException, письма нет")
    void rateLimitExceeded() {
        LoginLinkService service = service();
        when(redis.execute(any(RedisScript.class), eq(List.of(LoginLinkService.LINK_RATE_KEY_PREFIX + EMAIL)),
                eq(Long.toString(LoginLinkService.RATE_TTL.toSeconds())))).thenReturn(4L);

        assertThatThrownBy(() -> service.requestLoginLink(EMAIL))
                .isInstanceOf(OtpRateLimitExceededException.class);

        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("consumeLoginLink: валидный токен → ключ удалён (одноразовость), сессия выдана")
    void consumeIssuesSessionAndDeletesKey() {
        LoginLinkService service = service();
        when(redis.opsForValue()).thenReturn(values);
        String token = "tok";
        String key = LoginLinkService.LINK_KEY_PREFIX + LoginLinkService.sha256(token);
        UUID userId = UUID.randomUUID();
        when(values.get(key)).thenReturn(userId.toString());
        UserEntity verified = user(userId, true);
        when(userService.findById(userId)).thenReturn(Optional.of(verified));
        when(tokenService.generate(any(UUID.class), any(java.util.Collection.class))).thenReturn("access-token");
        when(refreshTokenService.issue(userId, "127.0.0.1"))
                .thenReturn(new IssuedRefreshToken(userId, "opaque", userId + ".opaque", Instant.EPOCH));

        LoginResult result = service.consumeLoginLink(token, "127.0.0.1");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshTokenCookie()).isEqualTo(userId + ".opaque");
        verify(redis).delete(key);
    }

    @Test
    @DisplayName("consumeLoginLink: неизвестный/использованный токен → OtpInvalidException")
    void consumeUnknownToken() {
        LoginLinkService service = service();
        when(redis.opsForValue()).thenReturn(values);

        assertThatThrownBy(() -> service.consumeLoginLink("nope", "127.0.0.1"))
                .isInstanceOf(OtpInvalidException.class);

        verify(redis, never()).delete(anyString());
        verify(userService, never()).findById(any(UUID.class));
    }

    @Test
    @DisplayName("consumeLoginLink: почта не подтверждена → EmailNotVerifiedException, ключ уже удалён")
    void consumeUnverifiedUser() {
        LoginLinkService service = service();
        when(redis.opsForValue()).thenReturn(values);
        String token = "tok";
        String key = LoginLinkService.LINK_KEY_PREFIX + LoginLinkService.sha256(token);
        UUID userId = UUID.randomUUID();
        when(values.get(key)).thenReturn(userId.toString());
        UserEntity unverified = user(userId, false);
        when(userService.findById(userId)).thenReturn(Optional.of(unverified));

        assertThatThrownBy(() -> service.consumeLoginLink(token, "127.0.0.1"))
                .isInstanceOf(EmailNotVerifiedException.class);

        verify(redis).delete(key);
        verify(refreshTokenService, never()).issue(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("consumeLoginLink: null-токен → OtpInvalidException")
    void consumeNullToken() {
        LoginLinkService service = service();
        when(redis.opsForValue()).thenReturn(values);

        assertThatThrownBy(() -> service.consumeLoginLink(null, "127.0.0.1"))
                .isInstanceOf(OtpInvalidException.class);
    }
}
