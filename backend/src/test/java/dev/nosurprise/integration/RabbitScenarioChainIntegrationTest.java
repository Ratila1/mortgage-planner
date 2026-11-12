package dev.nosurprise.integration;

import dev.nosurprise.calc.repository.CalculationDocument;
import dev.nosurprise.config.RabbitMailConfig;
import dev.nosurprise.config.RabbitNotificationConfig;
import dev.nosurprise.config.RabbitPdfConfig;
import dev.nosurprise.config.RabbitScenarioConfig;
import dev.nosurprise.integration.support.AbstractIntegrationTest;
import dev.nosurprise.integration.support.AuthFixture;
import dev.nosurprise.integration.support.MailpitClient;
import dev.nosurprise.offer.event.OfferRateChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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
 * Полная RabbitMQ-цепочка на реальном брокере:
 * PUT /api/admin/offers/{id} → offer.rate.changed → scenario.recalc.q → пересчёт сценария →
 * scenario.recalculated → notification.q → SCENARIO_RECALCULATED → mail.requested → mail.send.q →
 * письмо в Mailpit. Плюс изоляция сценариев другого offer, идемпотентность по eventId и DLQ
 * после исчерпания retry.
 */
@DisplayName("RabbitMQ chain: offer.rate.changed → recalc → notification → mail")
class RabbitScenarioChainIntegrationTest extends AbstractIntegrationTest {

    private static final UUID AURORA_OFFER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOREAS_OFFER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PASSWORD = "Strong-pass-123";
    private static final String AURORA_INITIAL_RATE = "9.50";

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
    private GridFsTemplate gridFs;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthFixture auth;
    private MailpitClient mailpit;

    @BeforeEach
    void setUp() {
        auth = new AuthFixture(api, jdbc, redis, objectMapper);
        mailpit = new MailpitClient(objectMapper);
        purgeQueues();
        resetOfferRate(AURORA_OFFER_ID, AURORA_INITIAL_RATE);
        resetOfferRate(BOREAS_OFFER_ID, "10.20");
    }

    @Test
    @DisplayName("admin меняет ставку: сценарий пересчитан, notification и письмо содержат старые и новые значения")
    void adminRateChangeRecalculatesScenarioAndSendsMail() {
        AuthFixture.Account user = auth.registerAndVerify("rabbit-owner", PASSWORD);

        JsonNode created = createScenario("Aurora " + UUID.randomUUID(), AURORA_OFFER_ID,
                AURORA_INITIAL_RATE, user.accessToken());
        String scenarioId = created.get("id").asString();
        String oldCalcId = created.get("calcId").asString();
        String oldPayment = created.get("summary").get("monthlyPayment").asString();
        String oldOverpayment = created.get("summary").get("overpayment").asString();

        AuthFixture.Account admin = auth.loginExisting(ADMIN_EMAIL, ADMIN_PASSWORD);
        updateOfferRate(admin.accessToken(), AURORA_OFFER_ID, "Aurora Bank", "8.75", "10.00", 360);

        JsonNode recalculated = awaitRecalculated(user.accessToken(), scenarioId, oldCalcId, "8.75");
        String newCalcId = recalculated.get("calcId").asString();
        String newPayment = recalculated.get("summary").get("monthlyPayment").asString();
        String newOverpayment = recalculated.get("summary").get("overpayment").asString();
        assertThat(newPayment).isNotEqualTo(oldPayment);
        assertThat(newOverpayment).isNotEqualTo(oldOverpayment);

        // Пересчёт создан и сохранён в MongoDB с новой ставкой.
        CalculationDocument calculation = mongo.findById(newCalcId, CalculationDocument.class);
        assertThat(calculation).as("расчёт нового calcId").isNotNull();
        assertThat(calculation.params().annualRate()).isEqualByComparingTo("8.75");

        // jsonb хранит объекты (не JSON-строки).
        assertThat(jsonbTypeOfScenario(scenarioId, "params")).isEqualTo("object");
        assertThat(jsonbTypeOfScenario(scenarioId, "summary")).isEqualTo("object");

        List<JsonNode> notifications = awaitNotifications(user.accessToken(), "SCENARIO_RECALCULATED", 1);
        JsonNode payload = notifications.get(0).get("payload");
        assertThat(payload.get("scenarioId").asString()).isEqualTo(scenarioId);
        assertThat(payload.get("calcId").asString()).isEqualTo(newCalcId);
        assertThat(payload.get("oldRate").asString()).isEqualTo(AURORA_INITIAL_RATE);
        assertThat(payload.get("newRate").asString()).isEqualTo("8.75");
        assertThat(payload.get("oldMonthlyPayment").asString()).isEqualTo(oldPayment);
        assertThat(payload.get("newMonthlyPayment").asString()).isEqualTo(newPayment);
        assertThat(payload.get("oldOverpayment").asString()).isEqualTo(oldOverpayment);
        assertThat(payload.get("newOverpayment").asString()).isEqualTo(newOverpayment);
        assertThat(notificationPayloadType(user.id())).isEqualTo("object");

        List<MailpitClient.Mail> mails = awaitRecalculatedMails(user.email(), 1);
        String body = mails.get(0).text();
        assertThat(body)
                .contains("было: 9.50%", "стало: 8.75%")
                .contains("платёж " + oldPayment, "платёж " + newPayment)
                .contains("переплата " + oldOverpayment, "переплата " + newOverpayment);
    }

    @Test
    @DisplayName("сценарий другого пользователя на другом offer не пересчитывается")
    void scenarioOnAnotherOfferIsNotRecalculated() {
        AuthFixture.Account owner = auth.registerAndVerify("rabbit-owner", PASSWORD);
        AuthFixture.Account other = auth.registerAndVerify("rabbit-other", PASSWORD);

        JsonNode ownerScenario = createScenario("Aurora " + UUID.randomUUID(), AURORA_OFFER_ID,
                AURORA_INITIAL_RATE, owner.accessToken());
        JsonNode otherScenario = createScenario("Boreas " + UUID.randomUUID(), BOREAS_OFFER_ID,
                "10.20", other.accessToken());

        String ownerScenarioId = ownerScenario.get("id").asString();
        String otherScenarioId = otherScenario.get("id").asString();
        String otherCalcId = otherScenario.get("calcId").asString();
        JsonNode otherBefore = scenario(other.accessToken(), otherScenarioId);

        AuthFixture.Account admin = auth.loginExisting(ADMIN_EMAIL, ADMIN_PASSWORD);
        updateOfferRate(admin.accessToken(), AURORA_OFFER_ID, "Aurora Bank", "8.75", "10.00", 360);

        awaitRecalculated(owner.accessToken(), ownerScenarioId,
                ownerScenario.get("calcId").asString(), "8.75");

        JsonNode otherAfter = scenario(other.accessToken(), otherScenarioId);
        assertThat(otherAfter.get("calcId").asString()).isEqualTo(otherCalcId);
        assertThat(otherAfter.get("params")).isEqualTo(otherBefore.get("params"));
        assertThat(otherAfter.get("summary")).isEqualTo(otherBefore.get("summary"));
        assertThat(notifications(other.accessToken(), "SCENARIO_RECALCULATED")).isEmpty();
        assertThat(recalculatedMails(other.email())).isEmpty();
    }

    @Test
    @DisplayName("повторная доставка того же eventId не создаёт дубликаты и не ломает сценарий")
    void duplicateEventIsIdempotent() {
        AuthFixture.Account user = auth.registerAndVerify("rabbit-duplicate", PASSWORD);
        JsonNode created = createScenario("Aurora duplicate " + UUID.randomUUID(), AURORA_OFFER_ID,
                AURORA_INITIAL_RATE, user.accessToken());
        String scenarioId = created.get("id").asString();

        OfferRateChanged duplicate = new OfferRateChanged(UUID.randomUUID(), Instant.now(), AURORA_OFFER_ID,
                new BigDecimal(AURORA_INITIAL_RATE), new BigDecimal("8.6"));
        rabbitTemplate.convertAndSend(RabbitMailConfig.EVENTS_EXCHANGE,
                RabbitScenarioConfig.OFFER_RATE_CHANGED_RK, duplicate);
        rabbitTemplate.convertAndSend(RabbitMailConfig.EVENTS_EXCHANGE,
                RabbitScenarioConfig.OFFER_RATE_CHANGED_RK, duplicate);

        JsonNode recalculated = awaitRecalculated(user.accessToken(), scenarioId,
                created.get("calcId").asString(), "8.6");
        String calcId = recalculated.get("calcId").asString();
        String payment = recalculated.get("summary").get("monthlyPayment").asString();

        // Сначала дожидаемся эффектов цепочки, затем проверяем, что дубликатов не появляется.
        awaitNotifications(user.accessToken(), "SCENARIO_RECALCULATED", 1);
        awaitRecalculatedMails(user.email(), 1);

        // Condition must hold continuously: duplicates must not appear later.
        await().atMost(Duration.ofSeconds(45))
                .during(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    assertThat(notifications(user.accessToken(), "SCENARIO_RECALCULATED")).hasSize(1);
                    assertThat(recalculatedMails(user.email())).hasSize(1);
                    JsonNode state = scenario(user.accessToken(), scenarioId);
                    assertThat(state.get("calcId").asString()).isEqualTo(calcId);
                    assertThat(state.get("summary").get("monthlyPayment").asString()).isEqualTo(payment);
                    assertThat(state.get("params").get("annualRate").asString()).isEqualTo("8.6");
                });

        // PDF-цепочка не запускалась: GridFS-файла для сценария нет.
        assertThat(gridFs.findOne(new Query(Criteria.where("filename")
                .is("scenario-" + scenarioId + ".pdf")))).isNull();
    }

    @Test
    @DisplayName("невалидный offer.rate.changed после retry попадает в scenario.recalc.q.dlq")
    void invalidEventGoesToDlqAfterRetries() {
        rabbitAdmin.purgeQueue(RabbitScenarioConfig.SCENARIO_RECALC_DLQ, false);

        OfferRateChanged invalid = new OfferRateChanged(null, Instant.now(), AURORA_OFFER_ID,
                new BigDecimal(AURORA_INITIAL_RATE), new BigDecimal("8.75"));
        rabbitTemplate.convertAndSend(RabbitMailConfig.EVENTS_EXCHANGE,
                RabbitScenarioConfig.OFFER_RATE_CHANGED_RK, invalid);

        AtomicReference<Message> dead = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Message message = rabbitTemplate.receive(RabbitScenarioConfig.SCENARIO_RECALC_DLQ, 200);
                    assertThat(message).as("сообщение в scenario.recalc.q.dlq").isNotNull();
                    dead.set(message);
                });

        Message message = dead.get();
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo(RabbitScenarioConfig.OFFER_RATE_CHANGED_RK);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("offerId", AURORA_OFFER_ID.toString());
    }

    private JsonNode createScenario(String name, UUID offerId, String annualRate, String accessToken) {
        String params = "{\"price\":\"12000000\",\"downPayment\":\"3000000\",\"termMonths\":180,"
                + "\"annualRate\":\"" + annualRate + "\",\"type\":\"ANNUITY\",\"earlyRepayments\":[]}";
        String body = "{\"name\":\"" + name + "\",\"params\":" + params
                + ",\"offerId\":\"" + offerId + "\"}";
        ResponseEntity<String> response = api.postJson("/api/scenarios", body, accessToken);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private void updateOfferRate(String adminToken, UUID offerId, String bankName, String rate,
                                 String minDownPaymentPercent, int maxTermMonths) {
        String body = "{\"bankName\":\"" + bankName + "\",\"rate\":\"" + rate + "\","
                + "\"minDownPaymentPercent\":\"" + minDownPaymentPercent + "\","
                + "\"maxTermMonths\":" + maxTermMonths + ",\"active\":true}";
        ResponseEntity<String> response = api.putJson("/api/admin/offers/" + offerId, body, adminToken);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("rate").asString()).isEqualTo(rate);
    }

    private JsonNode awaitRecalculated(String accessToken, String scenarioId, String oldCalcId,
                                       String newRate) {
        AtomicReference<JsonNode> result = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    JsonNode scenario = scenario(accessToken, scenarioId);
                    assertThat(scenario.get("params").get("annualRate").asString()).isEqualTo(newRate);
                    assertThat(scenario.get("calcId").asString()).isNotEqualTo(oldCalcId);
                    result.set(scenario);
                });
        return result.get();
    }

    private JsonNode scenario(String accessToken, String scenarioId) {
        ResponseEntity<String> response = api.get("/api/scenarios", accessToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode scenario : objectMapper.readTree(response.getBody())) {
            if (scenarioId.equals(scenario.get("id").asString())) {
                return scenario;
            }
        }
        throw new AssertionError("scenario not found: " + scenarioId);
    }

    private List<JsonNode> notifications(String accessToken, String type) {
        ResponseEntity<String> response = api.get("/api/notifications", accessToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode notification : objectMapper.readTree(response.getBody())) {
            if (type.equals(notification.get("type").asString())) {
                result.add(notification);
            }
        }
        return result;
    }

    private List<JsonNode> awaitNotifications(String accessToken, String type, int expected) {
        AtomicReference<List<JsonNode>> result = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<JsonNode> found = notifications(accessToken, type);
                    assertThat(found).hasSize(expected);
                    result.set(found);
                });
        return result.get();
    }

    private List<MailpitClient.Mail> recalculatedMails(String email) {
        return mailpit.messagesTo(email, "переплата");
    }

    private List<MailpitClient.Mail> awaitRecalculatedMails(String email, int expected) {
        AtomicReference<List<MailpitClient.Mail>> result = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<MailpitClient.Mail> found = recalculatedMails(email);
                    assertThat(found).as("письма о пересчёте сценария для %s", email).hasSize(expected);
                    result.set(found);
                });
        return result.get();
    }

    private String jsonbTypeOfScenario(String scenarioId, String column) {
        return jdbc.queryForObject(
                "select jsonb_typeof(" + column + ") from saved_scenarios where id = cast(? as uuid)",
                String.class, scenarioId);
    }

    private String notificationPayloadType(UUID userId) {
        return jdbc.queryForObject(
                "select jsonb_typeof(payload) from notifications "
                        + "where user_id = cast(? as uuid) and type = 'SCENARIO_RECALCULATED'",
                String.class, userId.toString());
    }

    private void resetOfferRate(UUID offerId, String rate) {
        jdbc.update("update bank_offers set rate = cast(? as numeric), updated_at = now() "
                + "where id = cast(? as uuid)", rate, offerId.toString());
        redis.delete("offers:all");
    }

    private void purgeQueues() {
        for (String queue : List.of(
                RabbitScenarioConfig.SCENARIO_RECALC_Q,
                RabbitScenarioConfig.SCENARIO_RECALC_DLQ,
                RabbitScenarioConfig.NOTIFICATION_Q,
                RabbitScenarioConfig.NOTIFICATION_DLQ,
                RabbitMailConfig.MAIL_SEND_Q,
                RabbitMailConfig.MAIL_SEND_DLQ,
                RabbitNotificationConfig.OFFER_RATE_CHANGED_Q,
                RabbitNotificationConfig.OFFER_RATE_CHANGED_DLQ,
                RabbitPdfConfig.EXPORT_PDF_Q,
                RabbitPdfConfig.EXPORT_PDF_DLQ)) {
            rabbitAdmin.purgeQueue(queue, false);
        }
    }
}
