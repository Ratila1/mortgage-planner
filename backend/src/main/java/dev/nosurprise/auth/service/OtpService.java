package dev.nosurprise.auth.service;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class OtpService {

    static final String OTP_KEY_PREFIX = "otp:";
    static final String OTP_RATE_KEY_PREFIX = "otp:rate:";
    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration OTP_RATE_TTL = Duration.ofMinutes(10);
    static final int OTP_RATE_LIMIT = 3;

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

        String code = generateCode();
        String hash = sha256(code);
        redis.opsForValue().set(OTP_KEY_PREFIX + normalized, hash, OTP_TTL);

        publisher.publish("mail.requested", new MailRequested(
                UUID.randomUUID(), Instant.now(), normalized, "Код для входа",
                "Ваш код для входа: " + code + ". Код действителен 10 минут."));
    }

    public LoginResult verifyOtp(String email, String code, String issuedIp) {
        String normalized = normalize(email);
        String storedHash = redis.opsForValue().get(OTP_KEY_PREFIX + normalized);
        if (storedHash == null || !MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8), sha256(code).getBytes(StandardCharsets.UTF_8))) {
            throw new OtpInvalidException("invalid otp");
        }
        redis.delete(OTP_KEY_PREFIX + normalized);

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

    private void checkRateLimit(String email) {
        String key = OTP_RATE_KEY_PREFIX + email;
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            throw new IllegalStateException("redis INCR returned null for " + key);
        }
        if (count == 1L) {
            redis.expire(key, OTP_RATE_TTL);
        }
        if (count > OTP_RATE_LIMIT) {
            throw new OtpRateLimitExceededException("too many otp requests");
        }
    }

    private static String generateCode() {
        int value = new SecureRandom().nextInt(1_000_000);
        return String.format(Locale.ROOT, "%06d", value);
    }

    private static String sha256(String value) {
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