package dev.nosurprise.integration;

import com.mongodb.client.model.Filters;
import dev.nosurprise.config.RabbitMailConfig;
import dev.nosurprise.config.RabbitPdfConfig;
import dev.nosurprise.config.RabbitScenarioConfig;
import dev.nosurprise.integration.support.AbstractIntegrationTest;
import dev.nosurprise.integration.support.AuthFixture;
import dev.nosurprise.pdf.event.ExportPdfRequested;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PDF flow на реальной инфраструктуре: POST export → export.pdf.q → генерация PDF →
 * GridFS + notification PDF_READY → GET скачивание. Плюс изоляция владельца и
 * идемпотентность повторной доставки одного eventId.
 */
@DisplayName("PDF flow: export → GridFS → notification → download")
class PdfFlowIntegrationTest extends AbstractIntegrationTest {

    private static final UUID AURORA_OFFER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PASSWORD = "Strong-pass-123";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthFixture auth;

    @BeforeEach
    void setUp() {
        auth = new AuthFixture(api, jdbc, redis, objectMapper);
        purgeQueues();
    }

    @Test
    @DisplayName("экспорт создаёт PDF в GridFS и PDF_READY, скачивание отдаёт application/pdf")
    void exportCreatesPdfAndDownloadReturnsIt() {
        AuthFixture.Account owner = auth.registerAndVerify("pdf-owner", PASSWORD);
        JsonNode scenario = createScenario(owner.accessToken());
        String scenarioId = scenario.get("id").asString();
        String calcId = scenario.get("calcId").asString();

        ResponseEntity<String> export = api.postEmpty("/api/scenarios/" + scenarioId + "/export/pdf",
                owner.accessToken());
        assertThat(export.getStatusCode()).as(export.getBody()).isEqualTo(HttpStatus.ACCEPTED);

        Document file = awaitPdfFile(scenarioId);
        assertThat(file.getString("filename")).isEqualTo(fileName(scenarioId));
        Document metadata = file.get("metadata", Document.class);
        assertThat(metadata.getString("contentType")).isEqualTo("application/pdf");
        String fileId = file.getObjectId("_id").toHexString();

        JsonNode notification = awaitPdfNotification(owner.accessToken(), scenarioId);
        JsonNode payload = notification.get("payload");
        assertThat(payload.get("scenarioId").asString()).isEqualTo(scenarioId);
        assertThat(payload.get("calcId").asString()).isEqualTo(calcId);
        assertThat(payload.get("fileId").asString()).isEqualTo(fileId);
        assertThat(payload.get("fileName").asString()).isEqualTo(fileName(scenarioId));
        assertThat(payload.get("downloadUrl").asString()).isEqualTo("/api/scenarios/" + scenarioId + "/pdf");
        assertThat(payloadFileTypes(owner.id())).containsOnly("object");

        ResponseEntity<byte[]> download = api.getBytes("/api/scenarios/" + scenarioId + "/pdf",
                owner.accessToken());
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment")
                .contains(fileName(scenarioId));
        assertThat(download.getBody()).isNotNull();
        assertThat(download.getBody().length).isGreaterThan(1000);
        assertThat(new String(download.getBody(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        AuthFixture.Account foreign = auth.registerAndVerify("pdf-foreign", PASSWORD);
        assertThat(api.getBytes("/api/scenarios/" + scenarioId + "/pdf", foreign.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.postEmpty("/api/scenarios/" + scenarioId + "/export/pdf", foreign.accessToken())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pdfNotifications(foreign.accessToken(), scenarioId)).isEmpty();
    }

    @Test
    @DisplayName("повторная доставка того же eventId не создаёт второй GridFS-файл и notification")
    void duplicateEventIdDoesNotDuplicatePdfOrNotification() {
        AuthFixture.Account owner = auth.registerAndVerify("pdf-duplicate", PASSWORD);
        JsonNode scenario = createScenario(owner.accessToken());
        String scenarioId = scenario.get("id").asString();
        String calcId = scenario.get("calcId").asString();

        ExportPdfRequested event = new ExportPdfRequested(UUID.randomUUID(), Instant.now(),
                UUID.fromString(scenarioId), calcId, owner.id());
        rabbitTemplate.convertAndSend(RabbitMailConfig.EVENTS_EXCHANGE,
                RabbitPdfConfig.EXPORT_PDF_REQUESTED_RK, event);
        rabbitTemplate.convertAndSend(RabbitMailConfig.EVENTS_EXCHANGE,
                RabbitPdfConfig.EXPORT_PDF_REQUESTED_RK, event);

        Document file = awaitPdfFile(scenarioId);
        JsonNode notification = awaitPdfNotification(owner.accessToken(), scenarioId);
        assertThat(notification.get("payload").get("fileId").asString())
                .isEqualTo(file.getObjectId("_id").toHexString());

        // Условие должно держаться непрерывно: дубликаты не появляются позже.
        await().atMost(Duration.ofSeconds(45))
                .during(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    assertThat(pdfFileCount(scenarioId)).isEqualTo(1);
                    assertThat(pdfNotifications(owner.accessToken(), scenarioId)).hasSize(1);
                });
        assertThat(payloadFileTypes(owner.id())).containsOnly("object");
    }

    private JsonNode createScenario(String accessToken) {
        String params = "{\"price\":\"12000000\",\"downPayment\":\"3000000\",\"termMonths\":180,"
                + "\"annualRate\":\"9.50\",\"type\":\"ANNUITY\",\"earlyRepayments\":[]}";
        String body = "{\"name\":\"PDF " + UUID.randomUUID() + "\",\"params\":" + params
                + ",\"offerId\":\"" + AURORA_OFFER_ID + "\"}";
        ResponseEntity<String> response = api.postJson("/api/scenarios", body, accessToken);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private Document awaitPdfFile(String scenarioId) {
        AtomicReference<Document> result = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Document file = mongo.getCollection("fs.files")
                            .find(Filters.eq("filename", fileName(scenarioId)))
                            .first();
                    assertThat(file).as("PDF в GridFS для сценария %s", scenarioId).isNotNull();
                    result.set(file);
                });
        return result.get();
    }

    private long pdfFileCount(String scenarioId) {
        return mongo.getCollection("fs.files").countDocuments(Filters.eq("filename", fileName(scenarioId)));
    }

    private JsonNode awaitPdfNotification(String accessToken, String scenarioId) {
        AtomicReference<JsonNode> result = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<JsonNode> found = pdfNotifications(accessToken, scenarioId);
                    assertThat(found).hasSize(1);
                    result.set(found.get(0));
                });
        return result.get();
    }

    private List<JsonNode> pdfNotifications(String accessToken, String scenarioId) {
        ResponseEntity<String> response = api.get("/api/notifications", accessToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode notification : objectMapper.readTree(response.getBody())) {
            if (!"PDF_READY".equals(notification.get("type").asString())) {
                continue;
            }
            JsonNode payload = notification.get("payload");
            if (payload != null && payload.has("scenarioId")
                    && scenarioId.equals(payload.get("scenarioId").asString())) {
                result.add(notification);
            }
        }
        return result;
    }

    private List<String> payloadFileTypes(UUID userId) {
        return jdbc.queryForList(
                "select jsonb_typeof(payload) from notifications "
                        + "where user_id = cast(? as uuid) and type = 'PDF_READY'",
                String.class, userId.toString());
    }

    private static String fileName(String scenarioId) {
        return "scenario-" + scenarioId + ".pdf";
    }

    private void purgeQueues() {
        for (String queue : List.of(
                RabbitPdfConfig.EXPORT_PDF_Q,
                RabbitPdfConfig.EXPORT_PDF_DLQ,
                RabbitScenarioConfig.NOTIFICATION_Q,
                RabbitScenarioConfig.NOTIFICATION_DLQ)) {
            rabbitAdmin.purgeQueue(queue, false);
        }
    }
}
