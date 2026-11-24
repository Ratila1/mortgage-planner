package dev.nosurprise.policy.integration;

import dev.nosurprise.policy.config.RabbitConfig;
import dev.nosurprise.policy.scoring.ScoringCompleted;
import dev.nosurprise.policy.scoring.ScoringRequested;
import dev.nosurprise.policy.scoring.ScoringResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Полный путь через брокер (CREDIT_POLICY.md §7.2): loan.scoring.requested → расчёт → loan.scoring.completed.
 * Повторная доставка того же requestId не создаёт второй расчёт и возвращает тот же результат.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = "app.policy.jwt-secret=integration-secret-integration-secret-1")
class ScoringFlowIntegrationTest {

    private static final String RESULT_Q = "test.scoring-result.q";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin admin;

    @Autowired
    ScoringResultRepository results;

    @BeforeEach
    void bindResultQueue() {
        Queue queue = new Queue(RESULT_Q, false, false, true);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(RabbitConfig.EVENTS_EXCHANGE))
                .with(ScoringCompleted.ROUTING_KEY));
        admin.purgeQueue(RESULT_Q);
    }

    @Test
    void requestIsScoredAndRedeliveryReturnsTheSameResult() {
        UUID requestId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        ScoringRequested request = new ScoringRequested(UUID.randomUUID(), Instant.now(), requestId, applicationId,
                UUID.randomUUID(),
                new ScoringRequested.Applicant("Иванова", "Анна", null, "+375291234567",
                        LocalDate.now().minusYears(45), "FEMALE", "BY", "Минск"),
                new ScoringRequested.Loan(UUID.randomUUID(), "Лечение", new BigDecimal("0.80"),
                        new BigDecimal("500.00"), 10, new BigDecimal("570.00"), new BigDecimal("1500.00")),
                new ScoringRequested.History(3, 3, 0, 0, false, 0));

        rabbit.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, ScoringRequested.ROUTING_KEY, request);
        ScoringCompleted first = receive();
        rabbit.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, ScoringRequested.ROUTING_KEY, request);
        ScoringCompleted second = receive();

        assertThat(first.requestId()).isEqualTo(requestId);
        assertThat(first.applicationId()).isEqualTo(applicationId);
        assertThat(first.ratingClass()).isEqualTo("B1");
        assertThat(first.points()).isEqualTo(314);
        assertThat(first.decision()).isEqualTo("RECOMMEND");
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(results.findByApplicationIdOrderByCreatedAtDesc(applicationId)).hasSize(1);
    }

    private ScoringCompleted receive() {
        ScoringCompleted message = rabbit.receiveAndConvert(RESULT_Q, 10_000,
                new ParameterizedTypeReference<ScoringCompleted>() {
                });
        assertThat(message).as("scoring result within 10 s").isNotNull();
        return message;
    }
}
