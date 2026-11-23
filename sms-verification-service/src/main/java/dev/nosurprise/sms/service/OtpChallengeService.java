package dev.nosurprise.sms.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Единое место для OTP-челленджей обоих каналов: хранение хэша кода с TTL, счётчик попыток,
 * сжигание кода при исчерпании попыток и маркер «код уже использован».
 *
 * <p>Раньше эта логика была продублирована: {@code RedisOtpStore} для SMS и прямые вызовы
 * {@code StringRedisTemplate} в Telegram-сервисе. Каналы отличаются только ключами и набором
 * атрибутов (у Telegram это {@code userId}/{@code operationRef}), поведение сверки кода — общее.
 */
@Component
public class OtpChallengeService {

    public enum Outcome { VERIFIED, INVALID, TOO_MANY_ATTEMPTS, MISSING }

    /** Идемпотентное окно «код уже использован»: общий для SMS и Telegram. */
    public static final Duration USED_TTL = Duration.ofHours(24);

    private static final String HASH = "hash";
    private static final String ATTEMPTS = "attempts";

    private final StringRedisTemplate redis;
    private final OtpHasher hasher;

    public OtpChallengeService(StringRedisTemplate redis, OtpHasher hasher) {
        this.redis = redis;
        this.hasher = hasher;
    }

    public static String smsKey(UUID smsId) { return "sms:otp:" + smsId; }
    public static String smsUsedKey(UUID smsId) { return "sms:otp:used:" + smsId; }
    public static String telegramKey(UUID verificationId) { return "tg:otp:" + verificationId; }
    public static String telegramUsedKey(UUID verificationId) { return "tg:otp:used:" + verificationId; }

    /** Кладёт хэш кода вместе с атрибутами канала; счётчик попыток стартует с нуля. */
    public void store(String key, String code, Map<String, String> attributes, Duration ttl) {
        Map<String, String> data = new HashMap<>(attributes);
        data.put(HASH, hasher.hash(code));
        data.put(ATTEMPTS, "0");
        redis.opsForHash().putAll(key, data);
        redis.expire(key, ttl);
    }

    /** Атрибуты челленджа без служебных полей; {@code empty} — кода нет (истёк или сожжён). */
    public Optional<Map<String, String>> attributes(String key) {
        Map<Object, Object> stored = redis.opsForHash().entries(key);
        if (stored.isEmpty()) return Optional.empty();
        Map<String, String> attributes = new HashMap<>();
        stored.forEach((field, value) -> {
            String name = String.valueOf(field);
            if (!HASH.equals(name) && !ATTEMPTS.equals(name)) attributes.put(name, String.valueOf(value));
        });
        return Optional.of(attributes);
    }

    /** Сверяет код и ведёт счётчик попыток; последняя неверная попытка сжигает челлендж. */
    public Outcome verify(String key, String code, int maxAttempts) {
        Object hash = redis.opsForHash().get(key, HASH);
        if (hash == null) return Outcome.MISSING;
        if (hasher.verify(code, String.valueOf(hash))) return Outcome.VERIFIED;

        Long attempts = redis.opsForHash().increment(key, ATTEMPTS, 1);
        if (attempts != null && attempts >= maxAttempts) {
            delete(key);
            return Outcome.TOO_MANY_ATTEMPTS;
        }
        return Outcome.INVALID;
    }

    /** Общий preamble: если код уже использован — VERIFIED, иначе обычный verify. */
    public Outcome check(String challengeKey, String usedKey, String code, int maxAttempts) {
        if (isUsed(usedKey)) return Outcome.VERIFIED;
        return verify(challengeKey, code, maxAttempts);
    }

    public void delete(String key) {
        redis.delete(key);
    }

    public boolean isUsed(String usedKey) {
        return Boolean.TRUE.equals(redis.hasKey(usedKey));
    }

    public void markUsed(String usedKey, Duration ttl) {
        redis.opsForValue().set(usedKey, "1", ttl);
    }
}
