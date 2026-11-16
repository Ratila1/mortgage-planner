package dev.nosurprise.pdf;

import dev.nosurprise.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * К20: PDF-экспорт сквозь реальную очередь — POST /api/scenarios/{id}/export/pdf → 202,
 * PDF генерируется асинхронно (export.pdf.q → consumer → GridFS), после чего
 * готовый результат скачивается. Негатив: чужой сценарий → 404 SCENARIO_NOT_FOUND.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioPdfExportIT extends IntegrationTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        baseProps(registry);
    }

    @Test
    @DisplayName("export → 202; PDF генерируется асинхронно; скачивание возвращает готовый PDF")
    void exportPdf_generatesAsynchronously_downloadReturnsReadyPdf() {
        User user = registerVerifiedUser(uniqueEmail("pdf-export"), TEST_PASSWORD);
        String scenarioId = createScenario(user);

        HttpResult accepted = post("/api/scenarios/" + scenarioId + "/export/pdf", null, user.accessToken());
        assertThat(accepted.status()).as("export must be accepted: " + accepted.body()).isEqualTo(202);

        // PDF появляется асинхронно — ждём готовый файл повторными запросами
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            HttpResult download = call("GET", "/api/scenarios/" + scenarioId + "/pdf", null,
                    java.util.Map.of("Authorization", "Bearer " + user.accessToken()));
            assertThat(download.status()).as("pdf download: " + download.body()).isEqualTo(200);
            assertThat(download.headers().getOrDefault("content-type", java.util.List.of()))
                    .as("content-type")
                    .anyMatch(ct -> ct.startsWith("application/pdf"));
            assertThat(download.body()).as("pdf must start with %PDF signature").startsWith("%PDF");
        });

        // пользователь получает PDF_READY уведомление (§6.1 notifications)
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            HttpResult notifications = get("/api/notifications", user.accessToken());
            assertThat(notifications.status()).isEqualTo(200);
            assertThat(notifications.body()).contains("PDF_READY");
        });
    }

    @Test
    @DisplayName("негатив: экспорт чужого сценария → 404 SCENARIO_NOT_FOUND")
    void exportPdf_ofOtherUsersScenario_returns404ScenarioNotFound() {
        User owner = registerVerifiedUser(uniqueEmail("pdf-owner"), TEST_PASSWORD);
        String scenarioId = createScenario(owner);

        User stranger = registerVerifiedUser(uniqueEmail("pdf-stranger"), TEST_PASSWORD);

        HttpResult export = post("/api/scenarios/" + scenarioId + "/export/pdf", null, stranger.accessToken());
        assertThat(export.status()).isEqualTo(404);
        assertThat(objectMapper.readTree(export.body()).get("code").asString())
                .isEqualTo("SCENARIO_NOT_FOUND");

        HttpResult download = get("/api/scenarios/" + scenarioId + "/pdf", stranger.accessToken());
        assertThat(download.status()).isEqualTo(404);

        HttpResult notExisting = get("/api/scenarios/" + UUID.randomUUID() + "/pdf", stranger.accessToken());
        assertThat(notExisting.status()).isEqualTo(404);
    }

    private String createScenario(User user) {
        ObjectNode params = objectMapper.createObjectNode()
                .put("price", "1000000")
                .put("downPayment", "200000")
                .put("termMonths", 240)
                .put("annualRate", "10.00")
                .put("type", "ANNUITY");
        params.putArray("earlyRepayments");

        ObjectNode body = objectMapper.createObjectNode().put("name", "PDF plan");
        body.set("params", params);

        HttpResult created = post("/api/scenarios", body.toString(), user.accessToken());
        assertThat(created.status()).as("create scenario: " + created.body()).isEqualTo(201);
        JsonNode scenario = objectMapper.readTree(created.body());
        assertThat(scenario.get("id").asString()).isNotBlank();
        return scenario.get("id").asString();
    }
}
