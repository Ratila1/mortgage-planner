package dev.nosurprise.integration;

import dev.nosurprise.calc.repository.CalculationDocument;
import dev.nosurprise.config.RabbitMailConfig;
import dev.nosurprise.config.RabbitNotificationConfig;
import dev.nosurprise.config.RabbitPdfConfig;
import dev.nosurprise.config.RabbitScenarioConfig;
import dev.nosurprise.integration.support.AbstractIntegrationTest;
import dev.nosurprise.integration.support.StartupLogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke: полный Spring context на реальных PostgreSQL, MongoDB, Redis, RabbitMQ.
 * Ловит именно тот класс ошибок, который ломал запуск backend: конфликты имён бинов,
 * отсутствие bean'ов, неверные префиксы свойств, неприменённые миграции.
 */
@DisplayName("Smoke: полный старт приложения на реальной инфраструктуре")
class SmokeIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> FATAL_MARKERS = List.of(
            "APPLICATION FAILED TO START",
            "BeanDefinitionOverrideException",
            "Error creating bean with name",
            "Failed to configure a DataSource",
            "UnsatisfiedDependencyException",
            "BeanCreationException");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Flyway применил V1-V6, таблицы и seed offers на месте")
    void flywayMigrationsAndSchema() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where version is not null order by installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");

        List<Boolean> successes = jdbc.queryForList(
                "select success from flyway_schema_history where version is not null order by installed_rank",
                Boolean.class);
        assertThat(successes).hasSize(6).containsOnly(true);

        for (String table : List.of("users", "user_roles", "bank_offers", "saved_scenarios", "notifications")) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables"
                            + " where table_schema = 'public' and table_name = ?",
                    Integer.class, table);
            assertThat(count).as("таблица %s", table).isEqualTo(1);
        }

        Integer offers = jdbc.queryForObject("select count(*) from bank_offers", Integer.class);
        assertThat(offers).isEqualTo(4);

        BigDecimal auroraRate = jdbc.queryForObject(
                "select rate from bank_offers where id = '11111111-1111-1111-1111-111111111111'",
                BigDecimal.class);
        assertThat(auroraRate).isEqualByComparingTo("9.50");
    }

    @Test
    @DisplayName("MongoDB: индексы paramsHash, user+createdAt и TTL expireAt созданы")
    void mongoIndexes() {
        List<IndexInfo> indexes = mongo.indexOps(CalculationDocument.class).getIndexInfo();

        assertThat(indexes).extracting(IndexInfo::getName)
                .contains("params_hash_idx", "user_created_at_idx", "expire_at_ttl_idx");

        IndexInfo ttl = indexes.stream()
                .filter(index -> index.getName().equals("expire_at_ttl_idx"))
                .findFirst().orElseThrow();
        assertThat(ttl.getExpireAfter()).contains(Duration.ZERO);
    }

    @Test
    @DisplayName("Redis доступен на запись и чтение")
    void redisAvailable() {
        String key = "it:smoke:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "ok", Duration.ofMinutes(1));
            assertThat(redis.opsForValue().get(key)).isEqualTo("ok");
        } finally {
            redis.delete(key);
        }
    }

    @Test
    @DisplayName("RabbitMQ: exchange domain.events/domain.events.dlx и все очереди с DLQ созданы")
    void rabbitTopologyDeclared() {
        rabbit.execute(channel -> {
            channel.exchangeDeclarePassive(RabbitMailConfig.EVENTS_EXCHANGE);
            channel.exchangeDeclarePassive(RabbitMailConfig.DLX_EXCHANGE);
            return null;
        });

        List<String> queues = List.of(
                RabbitMailConfig.MAIL_SEND_Q, RabbitMailConfig.MAIL_SEND_DLQ,
                RabbitScenarioConfig.SCENARIO_RECALC_Q, RabbitScenarioConfig.SCENARIO_RECALC_DLQ,
                RabbitScenarioConfig.NOTIFICATION_Q, RabbitScenarioConfig.NOTIFICATION_DLQ,
                RabbitPdfConfig.EXPORT_PDF_Q, RabbitPdfConfig.EXPORT_PDF_DLQ,
                RabbitNotificationConfig.OFFER_RATE_CHANGED_Q, RabbitNotificationConfig.OFFER_RATE_CHANGED_DLQ);

        for (String queue : queues) {
            rabbit.execute(channel -> {
                channel.queueDeclarePassive(queue);
                return null;
            });
        }
    }

    @Test
    @DisplayName("/actuator/health = UP и GET /api/offers = 200 с seed-данными")
    void httpEndpointsRespond() {
        ResponseEntity<String> health = api.get("/actuator/health");
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> offers = api.get("/api/offers");
        assertThat(offers.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(offers.getBody());
        assertThat(body.size()).isEqualTo(4);
        assertThat(offers.getBody()).contains("Aurora Bank");
    }

    @Test
    @DisplayName("в логах старта нет фатальных ошибок")
    void noFatalStartupErrors() {
        List<String> errors = StartupLogCapture.errorMessages();
        assertThat(errors)
                .as("ERROR-логи старта: %s", errors)
                .noneMatch(message -> FATAL_MARKERS.stream().anyMatch(message::contains));
    }
}
