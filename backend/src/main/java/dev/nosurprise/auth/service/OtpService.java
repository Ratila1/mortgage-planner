package dev.nosurprise.auth.service;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class OtpService {

    static final String OTP_KEY_PREFIX = "otp:";
    static final String OTP_FAIL_KEY_PREFIX = "otp:fail:";
    static final String OTP_RATE_KEY_PREFIX = "otp:rate:";
    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration OTP_RATE_TTL = Duration.ofMinutes(10);
    static final int OTP_RATE_LIMIT = 3;
    /**
     * Защита от перебора 6-значного кода (§7): после N неудачных попыток подряд
     * код удаляется — подобрать его за время жизни невозможно.
     */
    static final int MAX_VERIFY_ATTEMPTS = 5;

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

    public OtpService(UserService userService, TokenService tokenService, RefreshTokenService refreshTokenService,
                      JwtProperties jwtProperties, Clock clock, RabbitEventPublisher publisher,
                      StringRedisTemplate redis) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.publisher = publisher;
        this.redis = redis;
    }

    public void requestOtp(String email) {
        String normalized = normalize(email);
        checkRateLimit(normalized);

        // Письмо уходит только на зарегистрированный адрес: несуществующие email иначе
        // бомбардируются письмами «код для входа» с произвольных IP (per-email лимит
        // не спасает). Ответ одинаковый в обоих случаях — существование не раскрывается.
        if (userService.findByEmail(normalized).isEmpty()) {
            return;
        }

        String code = generateCode();
        String hash = sha256(code);
        redis.opsForValue().set(OTP_KEY_PREFIX + normalized, hash, OTP_TTL);
        // Новый код — счётчик неудачных попыток сбрасывается (иначе старые неудачи
        // от предыдущего кода укорачивают жизнь свежему).
        redis.delete(OTP_FAIL_KEY_PREFIX + normalized);

        publisher.publish("mail.requested", new MailRequested(
                UUID.randomUUID(), clock.instant(), normalized, "Код для входа",
                "Ваш код для входа: " + code + ". Код действителен 10 минут."));
    }

    public LoginResult verifyOtp(String email, String code, String issuedIp) {
        String normalized = normalize(email);
        String storedHash = redis.opsForValue().get(OTP_KEY_PREFIX + normalized);
        if (storedHash == null) {
            throw new OtpInvalidException("invalid otp");
        }
        if (!MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8), sha256(code).getBytes(StandardCharsets.UTF_8))) {
            registerFailedAttempt(normalized);
            throw new OtpInvalidException("invalid otp");
        }
        redis.delete(OTP_KEY_PREFIX + normalized);
        redis.delete(OTP_FAIL_KEY_PREFIX + normalized);

        UserEntity user = userService.findByEmail(normalized)
                .orElseThrow(() -> new OtpInvalidException("invalid otp"));
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        IssuedRefreshToken refresh = refreshTokenService.issue(user.getId(), issuedIp);
        return new LoginResult(
                tokenService.generate(user.getId(), user.getRoles()),
                jwtProperties.accessTtl().toSeconds(),
                refresh.cookieValue());
    }

    /**
     * Учитывает неудачную попытку проверки (§7): INCR по email с TTL, а при достижении
     * лимита код удаляется — перебор становится невозможен, нужно запросить новый.
     * Счётчик живёт не дольше кода: новый requestOtp его сбрасывает.
     * INCR и EXPIRE атомарны (Lua, §6.3): ключ без TTL сделал бы счётчик вечным.
     */
    private void registerFailedAttempt(String normalized) {
        String failKey = OTP_FAIL_KEY_PREFIX + normalized;
        Long failures = redis.execute(INCR_EXPIRE, List.of(failKey),
                Long.toString(OTP_TTL.toSeconds()));
        if (failures != null && failures >= MAX_VERIFY_ATTEMPTS) {
            redis.delete(OTP_KEY_PREFIX + normalized);
            redis.delete(failKey);
        }
    }

    private void checkRateLimit(String email) {
        String key = OTP_RATE_KEY_PREFIX + email;
        Long count = redis.execute(INCR_EXPIRE, List.of(key),
                Long.toString(OTP_RATE_TTL.toSeconds()));
        if (count == null) {
            throw new IllegalStateException("redis INCR returned null for " + key);
        }
        if (count > OTP_RATE_LIMIT) {
            throw new OtpRateLimitExceededException("too many otp requests");
        }
    }

    private static String generateCode() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format(Locale.ROOT, "%06d", value);
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