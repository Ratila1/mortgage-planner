package dev.nosurprise.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Verification-токены подтверждения почты в Redis (§6.3): ключ verify:{token} → userId, TTL 24 ч.
 * Токен — 32 случайных байта, base64url (43 символа). read/delete разделены, чтобы
 * повторное подтверждение было безопасным (токен удаляется только после успешного
 * подтверждения — повторный запрос с тем же токеном идемпотентен).
 */
@Component
public class VerificationTokenStore {

    static final String KEY_PREFIX = "verify:";
    static final Duration TTL = Duration.ofHours(24);
    static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    public VerificationTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String create(UUID userId) {
        String token = newToken();
        redis.opsForValue().set(KEY_PREFIX + token, userId.toString(), TTL);
        return token;
    }

    public Optional<UUID> read(String token) {
        String value = redis.opsForValue().get(KEY_PREFIX + token);
        return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
    }

    public void delete(String token) {
        redis.delete(KEY_PREFIX + token);
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
