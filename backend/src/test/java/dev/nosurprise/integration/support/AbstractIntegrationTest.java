package dev.nosurprise.integration.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * База integration-тестов: полный Spring Boot context на реальной инфраструктуре
 * (Testcontainers), без моков. Помечена {@code @Tag("integration")}, поэтому
 * обычный {@code gradle test} её не запускает и Docker не требует.
 * HTTP-запросы идут напрямую в backend на случайном порту (не через nginx).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String JWT_SECRET = "integration-test-secret-at-least-32-characters-long";

    /** Seed-офферы (V5): Rabbit-тесты меняют их ставки — каждый тест-класс должен начинать с seed-состояния. */
    public static final String AURORA_OFFER_ID = "11111111-1111-1111-1111-111111111111";
    public static final String BOREAS_OFFER_ID = "22222222-2222-2222-2222-222222222222";
    public static final String AURORA_SEED_RATE = "9.50";
    public static final String BOREAS_SEED_RATE = "10.20";

    /** Первый ADMIN создаётся FirstAdminBootstrap при старте контекста из этих property. */
    public static final String ADMIN_EMAIL = "it-admin@nosurprise.test";
    public static final String ADMIN_PASSWORD = "Admin-pass-123";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @LocalServerPort
    protected int port;

    protected ApiClient api;

    @BeforeEach
    void createApiClient() {
        api = new ApiClient(port);
    }

    /**
     * Ставки офферов — разделяемое состояние в Postgres: класс, меняющий ставку
     * (Rabbit-цепочка), ломает соседние классы (PdfFlow, Smoke), которые создают
     * сценарии/проверяют seed при 9.50. Возвращаем seed-ставки перед каждым тестом.
     */
    @BeforeEach
    void resetSeedOfferRates() {
        jdbc.update("update bank_offers set rate = cast(? as numeric), updated_at = now() "
                + "where id = cast(? as uuid)", AURORA_SEED_RATE, AURORA_OFFER_ID);
        jdbc.update("update bank_offers set rate = cast(? as numeric), updated_at = now() "
                + "where id = cast(? as uuid)", BOREAS_SEED_RATE, BOREAS_OFFER_ID);
        redis.delete("offers:all");
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        StartupLogCapture.install();

        registry.add("spring.datasource.url", TestContainersEnvironment.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestContainersEnvironment.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestContainersEnvironment.POSTGRES::getPassword);

        registry.add("spring.mongodb.uri", () -> TestContainersEnvironment.MONGO.getConnectionString() + "/nosurprise");

        registry.add("spring.data.redis.host", TestContainersEnvironment.REDIS::getHost);
        registry.add("spring.data.redis.port",
                () -> TestContainersEnvironment.REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", TestContainersEnvironment.RABBIT::getHost);
        registry.add("spring.rabbitmq.port", TestContainersEnvironment.RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", TestContainersEnvironment.RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", TestContainersEnvironment.RABBIT::getAdminPassword);

        registry.add("spring.mail.host", TestContainersEnvironment.MAILPIT::getHost);
        registry.add("spring.mail.port", () -> TestContainersEnvironment.MAILPIT.getMappedPort(1025));

        registry.add("app.base-url", () -> "http://localhost:8080");
        registry.add("app.jwt.secret", () -> JWT_SECRET);
        // Service-to-service JWT для SMS-сервиса подписывается тем же секретом, что и access-токены.
        registry.add("app.sms-client.service-jwt-secret", () -> JWT_SECRET);

        // Первый ADMIN (§6.1): нужен для PUT /api/admin/offers в RabbitMQ-сценарии.
        registry.add("APP_ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("APP_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);

        // Rate limit не должен мешать интеграционным сценариям.
        registry.add("app.ratelimit.calc.anonymous-per-minute", () -> 1000);
        registry.add("app.ratelimit.calc.authenticated-per-minute", () -> 1000);
        registry.add("app.ratelimit.auth.login-per-minute", () -> 1000);
        registry.add("app.ratelimit.auth.link-consume-per-minute", () -> 1000);
        registry.add("app.ratelimit.auth.register-per-minute", () -> 1000);
        registry.add("app.ratelimit.auth.link-request-per-minute", () -> 1000);
        registry.add("app.ratelimit.auth.refresh-per-minute", () -> 1000);
        registry.add("app.ratelimit.auth.logout-per-minute", () -> 1000);
    }
}
