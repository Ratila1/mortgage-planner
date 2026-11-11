package dev.nosurprise.integration.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Одноразовая инфраструктура для integration-тестов: реальные PostgreSQL, MongoDB,
 * Redis, RabbitMQ и Mailpit (Testcontainers, без Testcontainers Cloud).
 * Контейнеры — статические singleton'ы: поднимаются один раз на JVM и переиспользуются
 * всеми integration-тестами одного запуска; удаляются Ryuk при завершении JVM.
 * Образы совпадают с docker-compose.yml.
 */
public final class TestContainersEnvironment {

    public static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("nosurprise")
                    .withUsername("nosurprise")
                    .withPassword("nosurprise")
                    .withStartupTimeout(Duration.ofMinutes(2));

    public static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:8"));

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(1)));

    public static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"))
                    .withAdminUser("guest")
                    .withAdminPassword("guest")
                    .withStartupTimeout(Duration.ofMinutes(2));

    public static final GenericContainer<?> MAILPIT =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:latest"))
                    .withExposedPorts(1025, 8025)
                    // Mailpit по умолчанию делает reverse DNS клиента SMTP: в Docker это
                    // ~10 c таймаута перед приветствием на каждое соединение.
                    .withEnv("MP_SMTP_DISABLE_RDNS", "true")
                    .waitingFor(Wait.forHttp("/api/v1/messages")
                            .forPort(8025)
                            .withStartupTimeout(Duration.ofMinutes(1)));

    static {
        POSTGRES.start();
        MONGO.start();
        REDIS.start();
        RABBIT.start();
        MAILPIT.start();
    }

    private TestContainersEnvironment() {
    }

    /** HTTP API Mailpit для проверки отправленных писем. */
    public static String mailpitApiUrl() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }
}
