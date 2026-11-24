package dev.nosurprise.bureau.report;

import dev.nosurprise.bureau.config.BureauProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Идемпотентность запросов (CREDIT_BUREAU.md §7.2): тот же Idempotency-Key от того же провайдера → тот же ответ
 * без новой строки в журнале, поэтому повтор после сетевого сбоя не оплачивается дважды. Ответ хранится
 * в Redis 24 часа вместе с хешем параметров: тот же ключ с другими параметрами — ошибка клиента, а не повтор.
 */
@Component
public class IdempotencyStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final BureauProperties properties;

    public IdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper, BureauProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<Entry> find(UUID providerId, String idempotencyKey) {
        String json = redis.opsForValue().get(key(providerId, idempotencyKey));
        return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, Entry.class));
    }

    public void save(UUID providerId, String idempotencyKey, Entry entry) {
        redis.opsForValue().set(key(providerId, idempotencyKey), objectMapper.writeValueAsString(entry),
                properties.idempotencyTtl());
    }

    private static String key(UUID providerId, String idempotencyKey) {
        return "bureau:idem:" + providerId + ":" + idempotencyKey;
    }

    public record Entry(String queryHash, CreditReportResponse response) {
    }
}
