package dev.nosurprise.auth.service;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Base64;

/**
 * Вход по ссылке из письма (magic link, §7) вместо одноразового кода:
 * POST /api/auth/login-link {email} → письмо со ссылкой /login?token=...,
 * POST /api/auth/login-link/consume {token} → сессия.
 * Токен — 32 случайных байта base64url; в Redis лежит только SHA-256-хэш
 * (ключ loginlink:{hash} → userId), как и раньше с OTP-кодом. Ссылка одноразовая:
 * consume удаляет ключ сразу после успешного чтения — перехваченная ссылка
 * бесполезна после первого использования. TTL 10 минут.
 * Токен 256 бит — перебор невозможен, счётчик неудачных попыток не нужен
 * (в отличие от 6-значного кода). Rate limit на выпуск ссылок сохранён.
 */
@Service
public class LoginLinkService {

    static final String LINK_KEY_PREFIX = "loginlink:";
    static final String LINK_RATE_KEY_PREFIX = "loginlink:rate:";
    static final Duration LINK_TTL = Duration.ofMinutes(10);
    static final Duration RATE_TTL = Duration.ofMinutes(10);
    static final int RATE_LIMIT = 3;
    static final int TOKEN_BYTES = 32;

    /** Атомарный INCR+EXPIRE (§6.3): счётчик никогда не остаётся без TTL. */
    private static final RedisScript<Long> INCR_EXPIRE = RedisScript.of(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """,
            Long.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserService userService;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final RabbitEventPublisher publisher;
    private final StringRedisTemplate redis;
    private final String baseUrl;

    public LoginLinkService(UserService userService, TokenService tokenService,
                            RefreshTokenService refreshTokenService, JwtProperties jwtProperties,
                            Clock clock, RabbitEventPublisher publisher, StringRedisTemplate redis,
                            @Value("${app.base-url}") String baseUrl) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.publisher = publisher;
        this.redis = redis;
        this.baseUrl = baseUrl;
    }

    public void requestLoginLink(String email) {
        String normalized = normalize(email);
        checkRateLimit(normalized);

        // Письмо уходит только на зарегистрированный адрес: несуществующие email иначе
        // бомбардируются письмами «ссылка для входа» с произвольных IP (per-email лимит
        // не спасает). Ответ одинаковый в обоих случаях — существование не раскрывается.
        var userOpt = userService.findByEmail(normalized);
        if (userOpt.isEmpty()) {
            return;
        }

        String token = newToken();
        redis.opsForValue().set(LINK_KEY_PREFIX + sha256(token), userOpt.get().getId().toString(), LINK_TTL);

        String loginUrl = baseUrl + "/login?token=" + token;
        publisher.publish("mail.requested", new MailRequested(
                UUID.randomUUID(), clock.instant(), normalized, "Ссылка для входа",
                "Войдите по ссылке: " + loginUrl
                        + " . Ссылка действительна 10 минут и работает один раз."));
    }

    public LoginResult consumeLoginLink(String token, String issuedIp) {
        String hash = sha256(token == null ? "" : token);
        String stored = redis.opsForValue().get(LINK_KEY_PREFIX + hash);
        if (stored == null) {
            throw new OtpInvalidException("invalid login link");
        }
        // Одноразовость: ключ удаляется сразу после успешного чтения, до выдачи сессии.
        redis.delete(LINK_KEY_PREFIX + hash);

        UUID userId = UUID.fromString(stored);
        UserEntity user = userService.findById(userId)
                .orElseThrow(() -> new OtpInvalidException("invalid login link"));
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        if (user.isBlocked()) {
            throw new dev.nosurprise.user.UserBlockedException();
        }
        IssuedRefreshToken refresh = refreshTokenService.issue(user.getId(), issuedIp);
        return new LoginResult(
                tokenService.generate(user.getId(), user.getRoles()),
                jwtProperties.accessTtl().toSeconds(),
                refresh.cookieValue());
    }

    private void checkRateLimit(String email) {
        String key = LINK_RATE_KEY_PREFIX + email;
        Long count = redis.execute(INCR_EXPIRE, List.of(key), Long.toString(RATE_TTL.toSeconds()));
        if (count == null) {
            throw new IllegalStateException("redis INCR returned null for " + key);
        }
        if (count > RATE_LIMIT) {
            throw new OtpRateLimitExceededException("too many login link requests");
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
