package dev.nosurprise.common.otp;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Одноразовые 6-значные коды в Redis для подтверждения телефона и подписи договора (MICROLOANS.md §3.10, §4.4).
 * В Redis лежит только SHA-256 кода, плюс привязка (телефон, хэш договора) и счётчик попыток.
 * Код живёт 5 минут, после 3 неверных вводов сгорает, повторная отправка не чаще раза в 60 секунд.
 */
@Component
public class OneTimeCodeStore {

    public static final int MAX_ATTEMPTS = 3;
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    /** Общая пауза на все SMS пользователя — защита от SMS-бомбардировки. */
    static final Duration COOLDOWN = Duration.ofSeconds(60);

    private static final String FIELD_HASH = "hash";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    public OneTimeCodeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Выпускает новый код и возвращает его открытым текстом (для отправки по SMS).
     * binding — то, к чему привязан код (телефон, хэш договора): при проверке его сверяет вызывающий.
     * cooldownKey — ключ паузы, по которому ограничиваетсяrepeat отправка. Кулдаун общего назначения
     * хранится отдельно по пользователю, иначе можно было бы бомбить SMS, меняя ключ кода.
     */
    public String issue(String key, String cooldownKey, Map<String, String> binding) {
        Boolean allowed = redis.opsForValue().setIfAbsent(cooldownKey, "1", COOLDOWN);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new CodeCooldownException();
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Map<String, String> fields = new HashMap<>(binding);
        fields.put(FIELD_HASH, sha256(code));
        fields.put(FIELD_ATTEMPTS, "0");
        redis.delete(key);
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, CODE_TTL);
        return code;
    }

    /**
     * Проверяет код. При неверном коде увеличивает счётчик; на третьей ошибке удаляет код (BURNED).
     * При успехе код НЕ удаляется: вызывающий удаляет его после коммита своей транзакции ({@link #delete}).
     */
    public CodeCheck verify(String key, String code) {
        Map<Object, Object> stored = redis.opsForHash().entries(key);
        if (stored.isEmpty()) {
            return new CodeCheck(CodeCheck.Result.MISSING, Map.of());
        }
        Map<String, String> binding = new HashMap<>();
        stored.forEach((k, v) -> binding.put(String.valueOf(k), String.valueOf(v)));
        String expected = binding.remove(FIELD_HASH);
        binding.remove(FIELD_ATTEMPTS);
        boolean matches = code != null && expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), sha256(code).getBytes(StandardCharsets.UTF_8));
        if (matches) {
            return new CodeCheck(CodeCheck.Result.OK, binding);
        }
        Long attempts = redis.opsForHash().increment(key, FIELD_ATTEMPTS, 1);
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            redis.delete(key);
            return new CodeCheck(CodeCheck.Result.BURNED, binding);
        }
        return new CodeCheck(CodeCheck.Result.INVALID, binding);
    }

    public void delete(String key) {
        redis.delete(key);
    }

    /**
     * Отзыв только что выпущенного кода, если SMS не ушло: удаляет и код, и паузу,
     * иначе клиент 60 секунд не смог бы запросить код повторно за чужую ошибку.
     */
    public void revoke(String key, String cooldownKey) {
        redis.delete(java.util.List.of(key, cooldownKey));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Результат проверки и сохранённая привязка кода. */
    public record CodeCheck(Result result, Map<String, String> binding) {

        public enum Result { OK, INVALID, BURNED, MISSING }

        public Optional<String> bound(String name) {
            return Optional.ofNullable(binding.get(name));
        }
    }
}
