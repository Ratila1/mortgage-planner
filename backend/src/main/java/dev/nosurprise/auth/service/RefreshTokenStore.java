package dev.nosurprise.auth.service;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-хранилище refresh-токенов (§6.3): ключ refresh:{userId}:{sha256(token)} →
 * метаданные, TTL 30 дн. Сам opaque-токен не хранится — только его SHA-256 в имени ключа.
 * Метаданные — простая строка createdAt|issuedIp (без сериализационных зависимостей).
 */
@Component
public class RefreshTokenStore {

    static final String KEY_PREFIX = "refresh:";
    static final Duration TTL = Duration.ofDays(30);
    static final String METADATA_SEPARATOR = "|";

    private final StringRedisTemplate redis;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void create(UUID userId, String token, RefreshTokenMetadata metadata) {
        redis.opsForValue().set(key(userId, token), toValue(metadata), TTL);
    }

    public Optional<RefreshTokenMetadata> read(UUID userId, String token) {
        String value = redis.opsForValue().get(key(userId, token));
        if (value == null) {
            return Optional.empty();
        }
        return parse(value);
    }

    /**
     * Атомарно забирает токен (§7): GETDEL — читает значение и удаляет ключ одним
     * вызовом. Защита от гонки ротации: два параллельных /refresh с одним и тем же
     * токеном не могут оба пройти проверку — второй получает отсутствие ключа.
     */
    public Optional<RefreshTokenMetadata> consume(UUID userId, String token) {
        String value = redis.execute((RedisCallback<String>) connection -> {
            byte[] raw = connection.stringCommands()
                    .getDel(key(userId, token).getBytes(StandardCharsets.UTF_8));
            return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
        });
        if (value == null) {
            return Optional.empty();
        }
        return parse(value);
    }

    public void delete(UUID userId, String token) {
        redis.delete(key(userId, token));
    }

    static String key(UUID userId, String token) {
        return KEY_PREFIX + userId + ":" + TokenService.sha256Hex(token);
    }

    static String toValue(RefreshTokenMetadata metadata) {
        return metadata.createdAt() + METADATA_SEPARATOR
                + (metadata.issuedIp() == null ? "" : metadata.issuedIp());
    }

    /** Повреждённое значение трактуем как отсутствие токена (Optional.empty). */
    private Optional<RefreshTokenMetadata> parse(String value) {
        int separator = value.indexOf(METADATA_SEPARATOR);
        if (separator <= 0) {
            return Optional.empty();
        }
        try {
            Instant createdAt = Instant.parse(value.substring(0, separator));
            String issuedIp = value.substring(separator + 1);
            return Optional.of(new RefreshTokenMetadata(createdAt, issuedIp.isEmpty() ? null : issuedIp));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
