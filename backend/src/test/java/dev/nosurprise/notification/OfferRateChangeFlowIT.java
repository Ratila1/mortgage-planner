package dev.nosurprise.notification;

import dev.nosurprise.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * К19: сквозной путь через реальный RabbitMQ — админ меняет ставку предложения
 * (PUT /api/admin/offers/{id}) → событие offer.rate.changed → consumer пересчитывает
 * затронутый сценарий пользователя → письмо-уведомление реально доставлено (SMTP-заглушка).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfferRateChangeFlowIT extends IntegrationTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        baseProps(registry);
    }

    @Test
    @DisplayName("PUT ставки админом → пересчёт сценария + уведомление в API + письмо по SMTP")
    void adminChangesOfferRate_recalcsScenario_notifiesUserByEmail() {
        String adminToken = accessTokenFrom(login(ADMIN_EMAIL, ADMIN_PASSWORD));

        // админ создаёт предложение (ставка 12.00)
        JsonNode offer = createOffer(adminToken, "IT Bank", "12.00");

        // верифицированный пользователь сохраняет сценарий с этим предложением
        User user = registerVerifiedUser(uniqueEmail("rate-change"), TEST_PASSWORD);
        JsonNode scenario = createScenario(user, offer.get("id").asString(), "12.00");
        BigDecimal oldMonthlyPayment = scenario.get("summary").get("monthlyPayment").decimalValue();

        // админ меняет ставку 12.00 → 15.00 → событие offer.rate.changed (после коммита)
        HttpResult updated = put("/api/admin/offers/" + offer.get("id").asString(), objectNode()
                .put("bankName", "IT Bank")
                .put("rate", "15.00")
                .put("minDownPaymentPercent", "20.00")
                .put("maxTermMonths", 360)
                .put("active", true).toString(), adminToken);
        assertThat(updated.status()).as("offer update: " + updated.body()).isEqualTo(200);
        assertThat(updated.body()).contains("\"rate\":\"15.00\"");

        // consumer scenario.recalc.q пересчитывает сценарий с новой ставкой
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            HttpResult list = get("/api/scenarios", user.accessToken());
            assertThat(list.status()).isEqualTo(200);
            JsonNode mine = findScenario(objectMapper.readTree(list.body()), scenario.get("id").asString());
            assertThat(mine).as("scenario after recalc").isNotNull();
            assertThat(mine.get("params").get("annualRate").decimalValue())
                    .as("annualRate after recalc")
                    .isEqualByComparingTo("15");
            assertThat(mine.get("summary").get("monthlyPayment").decimalValue())
                    .as("monthly payment must change when rate changed")
                    .isNotEqualByComparingTo(oldMonthlyPayment);
        });

        // consumer notification.q пишет уведомление «было → стало»
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            HttpResult notifications = get("/api/notifications", user.accessToken());
            assertThat(notifications.status()).isEqualTo(200);
            JsonNode recalcNotification = null;
            for (JsonNode n : objectMapper.readTree(notifications.body())) {
                if ("SCENARIO_RECALCULATED".equals(n.get("type").asString())) {
                    recalcNotification = n;
                    break;
                }
            }
            assertThat(recalcNotification).as("SCENARIO_RECALCULATED notification").isNotNull();
            JsonNode payload = recalcNotification.get("payload");
            assertThat(payload.get("oldRate").decimalValue()).isEqualByComparingTo("12");
            assertThat(payload.get("newRate").decimalValue()).isEqualByComparingTo("15");
            assertThat(payload.get("newMonthlyPayment").decimalValue())
                    .isNotEqualByComparingTo(payload.get("oldMonthlyPayment").decimalValue());
        });

        // письмо-уведомление реально доставлено SMTP-заглушкой (mail.send.q → JavaMailSender)
        String mailBody = awaitEmailTo(user.email(), "15.00");
        assertThat(mailBody).contains("15");
    }

    @Test
    @DisplayName("негатив: пользователь без роли ADMIN не может менять ставку → 403 FORBIDDEN")
    void nonAdmin_cannotChangeOfferRate_returns403Forbidden() {
        User user = registerVerifiedUser(uniqueEmail("not-admin"), TEST_PASSWORD);

        HttpResult result = put("/api/admin/offers/" + UUID.randomUUID(), objectNode()
                .put("bankName", "Nope Bank")
                .put("rate", "9.99")
                .put("minDownPaymentPercent", "20.00")
                .put("maxTermMonths", 360)
                .put("active", true).toString(), user.accessToken());
        assertThat(result.status()).isEqualTo(403);
        assertThat(objectMapper.readTree(result.body()).get("code").asString()).isEqualTo("FORBIDDEN");
    }

    private JsonNode createOffer(String adminToken, String bankName, String rate) {
        HttpResult created = post("/api/admin/offers", objectNode()
                .put("bankName", bankName)
                .put("rate", rate)
                .put("minDownPaymentPercent", "20.00")
                .put("maxTermMonths", 360)
                .put("active", true).toString(), adminToken);
        assertThat(created.status()).as("create offer: " + created.body()).isEqualTo(201);
        JsonNode offer = objectMapper.readTree(created.body());
        assertThat(offer.get("id").asString()).isNotBlank();
        assertThat(offer.get("rate").asString()).isEqualTo(rate);
        return offer;
    }

    private JsonNode createScenario(User user, String offerId, String rate) {
        ObjectNode params = objectMapper.createObjectNode()
                .put("price", "3000000")
                .put("downPayment", "600000")
                .put("termMonths", 120)
                .put("annualRate", rate)
                .put("type", "ANNUITY");
        params.putArray("earlyRepayments");

        ObjectNode body = objectMapper.createObjectNode()
                .put("name", "My plan")
                .put("offerId", offerId);
        body.set("params", params);

        HttpResult created = post("/api/scenarios", body.toString(), user.accessToken());
        assertThat(created.status()).as("create scenario: " + created.body()).isEqualTo(201);
        JsonNode scenario = objectMapper.readTree(created.body());
        assertThat(scenario.get("id").asString()).isNotBlank();
        assertThat(scenario.get("calcId").asString()).isNotBlank();
        return scenario;
    }

    private static JsonNode findScenario(JsonNode list, String scenarioId) {
        for (JsonNode candidate : list) {
            if (scenarioId.equals(candidate.get("id").asString())) {
                return candidate;
            }
        }
        return null;
    }

    private ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }
}
