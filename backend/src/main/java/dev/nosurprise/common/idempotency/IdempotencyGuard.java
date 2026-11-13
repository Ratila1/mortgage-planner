package dev.nosurprise.common.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Общий guard идемпотентности consumer'ов (§8): claim берётся ДО обработки
 * (SET NX, TTL 15 мин — страховка от «зависшего» claim при краше процесса),
 * после успешной обработки ключ перезаписывается значением done с длинным TTL.
 * Ошибка обработки снимает claim — срабатывает retry, затем DLX.
 * Состояния: нет ключа → не обработано; "claimed" → обрабатывается; "done" → обработано.
 */
@Component
public class IdempotencyGuard {

    static final String CLAIMED = "claimed";
    static final String DONE = "done";

    /** Пока обработка идёт/падала — короткий TTL: повторная доставка может повторить работу. */
    static final Duration CLAIM_TTL = Duration.ofMinutes(15);
    /** Обработанное событие помним долго — защита от повторной доставки. */
    static final Duration DONE_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public IdempotencyGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Событие уже успешно обработано? */
    public boolean isDone(String key) {
        return DONE.equals(redis.opsForValue().get(key));
    }

    /**
     * Пытается забрать обработку события себе. false — другой consumer уже обрабатывает
     * (или ещё не завершил после краша): повторную работу пропускаем.
     */
    public boolean tryClaim(String key) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, CLAIMED, CLAIM_TTL));
    }

    /** Отмечает событие успешно обработанным. */
    public void complete(String key) {
        redis.opsForValue().set(key, DONE, DONE_TTL);
    }

    /** Снимает claim после ошибки обработки (чтобы retry отработал). */
    public void release(String key) {
        redis.delete(key);
    }
}
