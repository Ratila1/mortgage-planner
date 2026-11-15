package dev.nosurprise.calc.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-реализация {@link CalcCache} (§6.3): ключ `calc:{ownerScope}:{paramsHash}` → calcId,
 * TTL 1 ч. ownerScope изолирует владельцев расчётов (см. интерфейс).
 * Значение — только calcId (ObjectId в строке), данные расчёта живут в Mongo.
 */
@Component
public class RedisCalcCache implements CalcCache {

    static final String KEY_PREFIX = "calc:";
    public static final String ANONYMOUS_SCOPE = "anon";
    static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    public RedisCalcCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> findCalcId(String ownerScope, String paramsHash) {
        return Optional.ofNullable(redis.opsForValue().get(key(ownerScope, paramsHash)));
    }

    @Override
    public void save(String ownerScope, String paramsHash, String calcId) {
        redis.opsForValue().set(key(ownerScope, paramsHash), calcId, TTL);
    }

    @Override
    public void evict(String ownerScope, String paramsHash) {
        redis.delete(key(ownerScope, paramsHash));
    }

    static String key(String ownerScope, String paramsHash) {
        return KEY_PREFIX + ownerScope + ":" + paramsHash;
    }
}
