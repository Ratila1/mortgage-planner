package dev.nosurprise.config;

import dev.nosurprise.IntegrationTestBase;
import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.common.messaging.RabbitEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * К21: DLQ + retry на реальном RabbitMQ. SMTP недоступен (порт 1) → MailSendConsumer падает:
 * spring.rabbitmq.listener.simple.retry делает 3 попытки (паузы 1с → 2с), после исчерпания
 * сообщение reject-ится (default-requeue-rejected=false) и через domain.events.dlx попадает
 * в mail.send.q.dlq. Ретраи проверяем по числу redeliveries в статистике очереди и по
 * длительности до попадания в DLQ (Awaitility, без Thread.sleep).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DlqRetryIT extends IntegrationTestBase {

    private static final Pattern REDELIVERIES = Pattern.compile("\"redeliveries\":(\\d+)");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        baseProps(registry);
        // SMTP недоступен → JavaMailSender падает → retry → DLQ
        registry.add("spring.mail.port", () -> "1");
    }

    @Autowired
    private RabbitEventPublisher publisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    @DisplayName("падающее сообщение ретраится 3 раза и после исчерпания уходит в mail.send.q.dlq")
    void failingMailMessage_retriedThreeTimes_thenDeadLetteredToDlq() {
        String recipient = "dlq-retry-" + UUID.randomUUID() + "@example.com";
        String eventId = UUID.randomUUID().toString();
        MailRequested event = new MailRequested(
                UUID.fromString(eventId), Instant.now(), recipient, "DLQ retry test", "dead letter body");

        long startedAt = System.currentTimeMillis();
        publisher.publish(RabbitMailConfig.MAIL_REQUESTED_RK, event);

        // после исчерпания ретраев сообщение попадает в DLQ с сохранённым содержимым
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            Message dlqMessage = rabbitTemplate.receive("mail.send.q.dlq");
            assertThat(dlqMessage).as("message must arrive in mail.send.q.dlq").isNotNull();
            String payload = new String(dlqMessage.getBody(), StandardCharsets.UTF_8);
            assertThat(payload).as("DLQ payload").contains(recipient).contains(eventId);
        });
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        // ретраи делали паузы 1с и 2с → до DLQ минимум ~3с
        assertThat(elapsedMillis)
                .as("3 attempts with 1s+2s pauses must take at least 2.5s, was %d ms", elapsedMillis)
                .isGreaterThanOrEqualTo(2500L);

        // каждая повторная доставка фиксируется в статистике очереди как redelivery
        int redeliveries = queueRedeliveries("mail.send.q");
        assertThat(redeliveries)
                .as("message must have been re-delivered at least twice before DLQ")
                .isGreaterThanOrEqualTo(2);
    }

    /** redeliveries из статистики очереди через HTTP management API RabbitMQ. */
    private int queueRedeliveries(String queueName) {
        String userPass = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + RABBIT.getHttpPort() + "/api/queues/%2F/" + queueName))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("management api status").isEqualTo(200);
            Matcher matcher = REDELIVERIES.matcher(response.body());
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("management api call interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("management api call failed for " + queueName, ex);
        }
    }
}
