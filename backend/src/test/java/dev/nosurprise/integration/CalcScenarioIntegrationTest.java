package dev.nosurprise.integration;

import dev.nosurprise.calc.repository.CalculationDocument;
import dev.nosurprise.integration.support.AbstractIntegrationTest;
import dev.nosurprise.integration.support.AuthFixture;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calculation + scenarios на реальных PostgreSQL/MongoDB/Redis:
 * анонимный TTL-расчёт, авторизованный расчёт без TTL, владение сценариями,
 * list/compare/delete и запрет доступа к чужому сценарию.
 */
@DisplayName("Calculation + scenarios: Mongo TTL, владение, compare, delete")
class CalcScenarioIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Strong-pass-123";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthFixture auth;

    @BeforeEach
    void createAuthFixture() {
        auth = new AuthFixture(api, jdbc, redis, objectMapper);
    }

    @Test
    @DisplayName("анонимный расчёт: 200, график на весь срок, документ в Mongo с expireAt ~7 дней")
    void anonymousCalculationStoredWithTtl() {
        ResponseEntity<String> response = api.postJson("/api/calc",
                calcJson("10000000", "2000000", 240, "9.5"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode calc = objectMapper.readTree(response.getBody());
        String calcId = calc.get("calcId").asString();
        assertThat(ObjectId.isValid(calcId)).isTrue();

        assertThat(new BigDecimal(calc.get("loanAmount").asString())).isEqualByComparingTo("8000000");
        assertThat(new BigDecimal(calc.get("monthlyPayment").asString())).isPositive();
        assertThat(new BigDecimal(calc.get("overpayment").asString())).isPositive();
        assertThat(calc.get("termMonths").asInt()).isEqualTo(240);
        assertThat(calc.get("savings").isNull()).isTrue();
        assertThat(calc.get("schedule").size()).isEqualTo(240);
        assertThat(calc.get("schedule").get(0).get("month").asInt()).isEqualTo(1);

        // GET /api/calc/{calcId} — публичный, отдаёт тот же расчёт.
        ResponseEntity<String> fetched = api.get("/api/calc/" + calcId);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(fetched.getBody()).get("calcId").asString()).isEqualTo(calcId);

        CalculationDocument document = mongo.findById(calcId, CalculationDocument.class);
        assertThat(document).isNotNull();
        assertThat(document.userId()).isNull();
        assertThat(document.paramsHash()).isNotBlank();
        assertThat(document.createdAt()).isNotNull();
        assertThat(document.schedule()).hasSize(240);

        Instant expectedExpireAt = Instant.now().plus(Duration.ofDays(7));
        assertThat(document.expireAt())
                .as("expireAt анонимного расчёта = now + 7 дней")
                .isNotNull()
                .isBetween(expectedExpireAt.minus(Duration.ofMinutes(2)),
                        expectedExpireAt.plus(Duration.ofMinutes(2)));
    }

    @Test
    @DisplayName("авторизованный расчёт: userId без expireAt; сценарии create/list/compare/delete")
    void authorizedCalculationAndScenarioLifecycle() {
        AuthFixture.Account user = auth.registerAndVerify("calc", PASSWORD);

        ResponseEntity<String> calcResponse = api.postJson("/api/calc",
                calcJson("12000000", "3000000", 180, "10.2"), user.accessToken());
        assertThat(calcResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String calcId = objectMapper.readTree(calcResponse.getBody()).get("calcId").asString();

        CalculationDocument document = mongo.findById(calcId, CalculationDocument.class);
        assertThat(document).isNotNull();
        assertThat(document.userId()).isEqualTo(user.id().toString());
        assertThat(document.expireAt()).isNull();

        String nameA = "Scenario A " + UUID.randomUUID();
        String nameB = "Scenario B " + UUID.randomUUID();
        JsonNode scenarioA = createScenario(nameA, calcJson("12000000", "3000000", 180, "10.2"),
                user.accessToken());
        JsonNode scenarioB = createScenario(nameB, calcJson("12000000", "3000000", 180, "11.0"),
                user.accessToken());

        String idA = scenarioA.get("id").asString();
        String idB = scenarioB.get("id").asString();

        assertThat(scenarioA.get("name").asString()).isEqualTo(nameA);
        assertThat(scenarioA.get("calcId").asString()).isNotBlank();
        assertThat(scenarioA.get("summary").get("monthlyPayment").asString()).isNotBlank();

        assertThat(scenarioOwner(idA)).isEqualTo(user.id());
        assertThat(scenarioOwner(idB)).isEqualTo(user.id());
        // jsonb должен хранить объект, а не JSON-строку с экранированным содержимым.
        assertThat(jsonbType(idA, "params")).isEqualTo("object");
        assertThat(jsonbType(idA, "summary")).isEqualTo("object");

        ResponseEntity<String> list = api.get("/api/scenarios", user.accessToken());
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(list)).containsExactlyInAnyOrder(idA, idB);

        ResponseEntity<String> compare = api.get(
                "/api/scenarios/compare?ids=" + idA + "," + idB, user.accessToken());
        assertThat(compare.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(compare)).containsExactlyInAnyOrder(idA, idB);

        ResponseEntity<String> deleted = api.delete("/api/scenarios/" + idA, user.accessToken());
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(scenarioCount(idA)).isZero();
        assertThat(ids(api.get("/api/scenarios", user.accessToken()))).containsExactly(idB);
    }

    @Test
    @DisplayName("чужой сценарий недоступен: list/compare/delete → 404, анонимный доступ → 401")
    void foreignScenarioIsNotAccessible() {
        AuthFixture.Account owner = auth.registerAndVerify("owner", PASSWORD);
        AuthFixture.Account stranger = auth.registerAndVerify("stranger", PASSWORD);

        String ownerScenario = createScenario("Owner " + UUID.randomUUID(),
                calcJson("9000000", "1500000", 120, "8.9"), owner.accessToken())
                .get("id").asString();
        String strangerScenario = createScenario("Stranger " + UUID.randomUUID(),
                calcJson("9000000", "1500000", 120, "9.5"), stranger.accessToken())
                .get("id").asString();

        // list возвращает только свои сценарии.
        ResponseEntity<String> strangerList = api.get("/api/scenarios", stranger.accessToken());
        assertThat(strangerList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(strangerList)).containsExactly(strangerScenario);

        // compare с чужим id — 404, а не частичный результат.
        ResponseEntity<String> compare = api.get(
                "/api/scenarios/compare?ids=" + strangerScenario + "," + ownerScenario,
                stranger.accessToken());
        assertThat(compare.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(compare)).isEqualTo("SCENARIO_NOT_FOUND");

        // delete чужого сценария — 404, сценарий владельца остаётся.
        ResponseEntity<String> deleted = api.delete("/api/scenarios/" + ownerScenario, stranger.accessToken());
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(scenarioCount(ownerScenario)).isEqualTo(1);
        assertThat(scenarioOwner(ownerScenario)).isEqualTo(owner.id());

        assertThat(api.get("/api/scenarios").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.delete("/api/scenarios/" + ownerScenario).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JsonNode createScenario(String name, String paramsJson, String accessToken) {
        String body = "{\"name\":\"" + name + "\",\"params\":" + paramsJson + ",\"offerId\":null}";
        ResponseEntity<String> response = api.postJson("/api/scenarios", body, accessToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private List<String> ids(ResponseEntity<String> response) {
        List<String> ids = new ArrayList<>();
        objectMapper.readTree(response.getBody()).forEach(node -> ids.add(node.get("id").asString()));
        return ids;
    }

    private UUID scenarioOwner(String scenarioId) {
        String owner = jdbc.queryForObject(
                "select user_id::text from saved_scenarios where id = cast(? as uuid)", String.class, scenarioId);
        return owner == null ? null : UUID.fromString(owner);
    }

    private int scenarioCount(String scenarioId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from saved_scenarios where id = cast(? as uuid)", Integer.class, scenarioId);
        return count == null ? 0 : count;
    }

    private String jsonbType(String scenarioId, String column) {
        return jdbc.queryForObject(
                "select jsonb_typeof(" + column + ") from saved_scenarios where id = cast(? as uuid)",
                String.class, scenarioId);
    }

    private String code(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody()).get("code").asString();
    }

    private String calcJson(String price, String downPayment, int termMonths, String annualRate) {
        return "{\"price\":\"" + price + "\","
                + "\"downPayment\":\"" + downPayment + "\","
                + "\"termMonths\":" + termMonths + ","
                + "\"annualRate\":\"" + annualRate + "\","
                + "\"type\":\"ANNUITY\","
                + "\"earlyRepayments\":[]}";
    }
}
