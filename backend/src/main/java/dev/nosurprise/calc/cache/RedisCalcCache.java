package dev.nosurprise.calc.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-реализация {@link CalcCache} (§6.3): ключ `calc:{paramsHash}` → calcId, TTL 1 ч.
 * Значение — только calcId (ObjectId в строке), данные расчёта живут в Mongo.
 */
@Component
public class RedisCalcCache implements CalcCache {

    static final String KEY_PREFIX = "calc:";
    static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    public RedisCalcCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> findCalcId(String paramsHash) {
        return Optional.ofNullable(redis.opsForValue().get(KEY_PREFIX + paramsHash));
    }

    @Override
    public void save(String paramsHash, String calcId) {
        redis.opsForValue().set(KEY_PREFIX + paramsHash, calcId, TTL);
    }

    @Override
    public void evict(String paramsHash) {
        redis.delete(KEY_PREFIX + paramsHash);
    }
}
