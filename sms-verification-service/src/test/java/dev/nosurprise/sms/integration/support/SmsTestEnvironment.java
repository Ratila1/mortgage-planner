package dev.nosurprise.sms.integration.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * Одноразовая инфраструктура integration-тестов: PostgreSQL, Redis и RabbitMQ (Testcontainers).
 * Контейнеры — статические singleton'ы на JVM; удаляются Ryuk при завершении.
 * Образы совпадают с docker-compose.yml.
 * <p>
 * Схему {@code sms} создаём до старта Spring: приложение подключается с
 * {@code currentSchema=sms}, и без схемы первый же коннект Hikari упал бы.
 */
public final class SmsTestEnvironment {

    public static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("nosurprise")
                    .withUsername("nosurprise")
                    .withPassword("nosurprise")
                    .withStartupTimeout(Duration.ofMinutes(2));

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(1)));

    public static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"))
                    .withStartupTimeout(Duration.ofMinutes(2));

    static {
        POSTGRES.start();
        REDIS.start();
        RABBIT.start();
        createSmsSchema();
    }

    private static void createSmsSchema() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS sms");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create sms schema in test database", e);
        }
    }

    private SmsTestEnvironment() {
    }
}
