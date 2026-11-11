package dev.nosurprise.integration.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

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

    @LocalServerPort
    protected int port;

    protected ApiClient api;

    @BeforeEach
    void createApiClient() {
        api = new ApiClient(port);
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

        // APP_ADMIN_EMAIL/PASSWORD включаются на этапе RabbitMQ-теста (нужен admin).
        // Сейчас не задаём: bootstrap admin'а падает на существующем баге UserService.save
        // (новый entity с заранее заданным UUID → merge → StaleObjectStateException).

        // Rate limit не должен мешать интеграционным сценариям.
        registry.add("app.ratelimit.calc.anonymous-per-minute", () -> 1000);
        registry.add("app.ratelimit.calc.authenticated-per-minute", () -> 1000);
    }

    /** HTTP-клиент к backend, создаётся один раз на тестовый экземпляр. */
    protected RestClient http() {
        return api.client();
    }
}
