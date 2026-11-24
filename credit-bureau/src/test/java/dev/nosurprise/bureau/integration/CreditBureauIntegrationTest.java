package dev.nosurprise.bureau.integration;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Бюро целиком на реальных Postgres, Mongo и Redis (CREDIT_BUREAU.md §12): поиск в «базе монолита» (VIEW) →
 * займы из Mongo, ключи, лимиты, журнал, идемпотентность, песочница, наружу не утекает лишнее.
 * «База монолита» — вторая БД в том же контейнере с минимальной таблицей заявок и тем же VIEW, что в V16 монолита.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreditBureauIntegrationTest {

    static final String ADMIN_KEY = "test-admin-key-test-admin-key";
    static final String SANDBOX_KEY = "nsb_sandbox1_" + "s".repeat(43);

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("nosurprise_bureau");

    /** Минимальная копия схемы монолита: только колонки, которые читает VIEW (миграции V15, V16 монолита). */
    static final String MONOLITH_SCHEMA = """
            CREATE TABLE loan_applications (
                id uuid PRIMARY KEY, user_id uuid NOT NULL, status varchar(16) NOT NULL,
                applicant_last_name varchar(100) NOT NULL, applicant_first_name varchar(100) NOT NULL,
                applicant_middle_name varchar(100), applicant_phone varchar(20) NOT NULL);
            CREATE VIEW bureau_applicant_lookup AS
            SELECT a.user_id, a.id AS application_id, a.applicant_phone AS phone,
                   replace(lower(a.applicant_last_name), 'ё', 'е') AS last_name_norm,
                   replace(lower(a.applicant_first_name), 'ё', 'е') AS first_name_norm,
                   replace(lower(coalesce(a.applicant_middle_name, '')), 'ё', 'е') AS middle_name_norm
            FROM loan_applications a WHERE a.status IN ('ACTIVE', 'OVERDUE', 'REPAID');
            """;
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8"));
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        MONGO.start();
        REDIS.start();
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE nosurprise");
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
        try (var connection = java.sql.DriverManager.getConnection(monolithUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute(MONOLITH_SCHEMA);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String monolithUrl() {
        return POSTGRES.getJdbcUrl().replace("/nosurprise_bureau", "/nosurprise");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.mongodb.uri", () -> MONGO.getConnectionString() + "/nosurprise");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.lookup.url", CreditBureauIntegrationTest::monolithUrl);
        registry.add("app.lookup.username", POSTGRES::getUsername);
        registry.add("app.lookup.password", POSTGRES::getPassword);
        registry.add("app.bureau.admin-key", () -> ADMIN_KEY);
        registry.add("app.bureau.sandbox-key", () -> SANDBOX_KEY);
    }

    @LocalServerPort
    int port;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/bureau").build();
        // Лимиты и счётчики неудач — между тестами с чистого листа.
        var stale = redis.keys("bureau:*");
        if (stale != null && !stale.isEmpty()) {
            redis.delete(stale);
        }
    }

    @Test
    @DisplayName("без ключа и с неверным ключом — 401; Swagger открыт")
    void authentication() {
        assertThat(report(null, phone("+375291000000"), null).getStatusCode().value()).isEqualTo(401);
        ResponseEntity<String> wrong = report("nsb_abcdefgh_" + "x".repeat(43), phone("+375291000000"), null);
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);
        assertThat(code(wrong)).isEqualTo("INVALID_API_KEY");
        assertThat(get("/v3/api-docs").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("песочница: фиктивные заёмщики, однофамильцы → 409")
    void sandbox() {
        ResponseEntity<String> response = report(SANDBOX_KEY, phone("+375 29 000-00-01"), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json(response).get("totalLoans").asInt()).isEqualTo(3);
        assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("10");

        ResponseEntity<String> namesakes = report(SANDBOX_KEY, name("Тестов", "Тест"), null);
        assertThat(namesakes.getStatusCode().value()).isEqualTo(409);
        assertThat(code(namesakes)).isEqualTo("AMBIGUOUS_SUBJECT");
    }

    @Test
    @DisplayName("поиск по телефону, по ФИО и по обоим; в ответе только ACTIVE/REPAID и разрешённые поля; журнал")
    void searchModes() {
        Provider bank = newProvider("Bank A " + UUID.randomUUID(), 100, 1000);
        UUID user = UUID.randomUUID();
        String phone = "+37529" + (1_000_000 + (int) (Math.random() * 8_999_999));
        UUID repaid = application(user, "REPAID", "Сидорова", "Алёна", phone);
        application(user, "ACTIVE", "Сидорова", "Алёна", phone);
        history(user, repaid, "REPAID", "500.00");
        history(user, UUID.randomUUID(), "ACTIVE", "700.00");
        history(user, UUID.randomUUID(), "REJECTED", "900.00");

        ResponseEntity<String> byPhone = report(bank.apiKey(), phone(phone), null);
        assertThat(byPhone.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(byPhone);
        assertThat(body.get("found").asBoolean()).isTrue();
        assertThat(body.get("matchedBy").asString()).isEqualTo("PHONE");
        assertThat(body.get("totalLoans").asInt()).as("REJECTED в истории не отдаётся").isEqualTo(2);
        assertThat(byPhone.getBody()).doesNotContain("секретный", "monthlyIncome", "interest", "userId", phone);

        assertThat(json(report(bank.apiKey(), name("СИДОРОВА", "алена"), null)).get("matchedBy").asString())
                .isEqualTo("NAME");
        assertThat(json(report(bank.apiKey(), both(phone, "Сидорова", "Алёна"), null)).get("found").asBoolean())
                .isTrue();
        ResponseEntity<String> mismatch = report(bank.apiKey(), both(phone, "Петрова", "Алёна"), null);
        assertThat(json(mismatch).get("found").asBoolean()).as("оба параметра — совпасть должны оба").isFalse();
        assertThat(journal(bank.id())).isEqualTo(4);
    }

    @Test
    @DisplayName("заявки не ACTIVE/REPAID в поиск не попадают; однофамильцы → 409, не оплачивается")
    void onlyLoansAndAmbiguity() {
        Provider bank = newProvider("Bank B " + UUID.randomUUID(), 100, 1000);
        String surname = "Однофамилец" + UUID.randomUUID().toString().substring(0, 6);
        application(UUID.randomUUID(), "REJECTED", "Отказник", "Иван", "+375297777001");
        assertThat(json(report(bank.apiKey(), phone("+375297777001"), null)).get("found").asBoolean()).isFalse();

        application(UUID.randomUUID(), "ACTIVE", surname, "Иван", "+375297777002");
        application(UUID.randomUUID(), "REPAID", surname, "Иван", "+375297777003");
        ResponseEntity<String> ambiguous = report(bank.apiKey(), name(surname, "Иван"), null);

        assertThat(ambiguous.getStatusCode().value()).isEqualTo(409);
        assertThat(code(ambiguous)).isEqualTo("AMBIGUOUS_SUBJECT");
        assertThat(jdbc.queryForObject("select count(*) from report_requests where provider_id = ? and billable",
                Long.class, bank.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("лимит в минуту: сверх лимита — 429 с Retry-After, в журнал не пишется")
    void rateLimit() {
        Provider bank = newProvider("Bank C " + UUID.randomUUID(), 2, 1000);

        assertThat(report(bank.apiKey(), phone("+375291000000"), null).getStatusCode().value()).isEqualTo(200);
        assertThat(report(bank.apiKey(), phone("+375291000000"), null).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> limited = report(bank.apiKey(), phone("+375291000000"), null);

        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(journal(bank.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("повтор с тем же Idempotency-Key — тот же ответ, одна строка в журнале")
    void idempotency() {
        Provider bank = newProvider("Bank D " + UUID.randomUUID(), 100, 1000);

        JsonNode first = json(report(bank.apiKey(), phone("+375291000000"), "retry-1"));
        JsonNode second = json(report(bank.apiKey(), phone("+375291000000"), "retry-1"));
        ResponseEntity<String> other = report(bank.apiKey(), phone("+375291000009"), "retry-1");

        assertThat(second.get("reportId").asString()).isEqualTo(first.get("reportId").asString());
        assertThat(journal(bank.id())).isEqualTo(1);
        assertThat(other.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    @DisplayName("приостановка → 403, отзыв ключа → 401, оба сразу")
    void suspendAndRevoke() {
        Provider bank = newProvider("Bank E " + UUID.randomUUID(), 100, 1000);
        assertThat(admin("/api/admin/providers/" + bank.id() + "/suspend").getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> suspended = report(bank.apiKey(), phone("+375291000000"), null);
        assertThat(suspended.getStatusCode().value()).isEqualTo(403);
        assertThat(code(suspended)).isEqualTo("PROVIDER_SUSPENDED");

        admin("/api/admin/providers/" + bank.id() + "/activate");
        admin("/api/admin/providers/" + bank.id() + "/keys/" + bank.keyId() + "/revoke");
        assertThat(report(bank.apiKey(), phone("+375291000000"), null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("10 неверных ключей с одного адреса → 429 даже с верным ключом")
    void bruteForceBlocked() {
        Provider bank = newProvider("Bank F " + UUID.randomUUID(), 100, 1000);
        for (int i = 0; i < 10; i++) {
            report("nsb_abcdefgh_" + "y".repeat(43), phone("+375291000000"), null);
        }

        ResponseEntity<String> blocked = report(bank.apiKey(), phone("+375291000000"), null);

        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(code(blocked)).isEqualTo("TOO_MANY_AUTH_FAILURES");
    }

    @Test
    @DisplayName("без параметров и только фамилия → 400; админский API без ключа → 401")
    void validationAndAdminAuth() {
        Provider bank = newProvider("Bank G " + UUID.randomUUID(), 100, 1000);
        assertThat(report(bank.apiKey(), Map.of(), null).getStatusCode().value()).isEqualTo(400);
        assertThat(report(bank.apiKey(), Map.of("lastName", "Иванов"), null).getStatusCode().value()).isEqualTo(400);
        assertThat(get("/api/admin/providers").getStatusCode().value()).isEqualTo(401);
    }

    // --- данные ---

    /** Заявка в «базе монолита». */
    UUID application(UUID userId, String status, String lastName, String firstName, String phone) {
        UUID id = UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection(monolithUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
             var statement = connection.prepareStatement("INSERT INTO loan_applications VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, userId);
            statement.setString(3, status);
            statement.setString(4, lastName);
            statement.setString(5, firstName);
            statement.setString(6, null);
            statement.setString(7, phone);
            statement.executeUpdate();
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return id;
    }

    /** Документ credit_history, как пишет монолит: один на заявку, с полями, которые банку отдавать нельзя. */
    void history(UUID userId, UUID applicationId, String status, String amount) {
        Instant at = Instant.parse("2026-10-01T10:00:00Z");
        mongo.insert(new Document("_id", applicationId)
                .append("userId", userId)
                .append("schemaVersion", 1)
                .append("lastEventId", 10L)
                .append("status", status)
                .append("loan", new Document("amount", new Decimal128(new BigDecimal(amount)))
                        .append("interest", new Decimal128(new BigDecimal("50.00"))))
                .append("monthlyIncome", new Decimal128(new BigDecimal("2000.00")))
                .append("decisionComment", "секретный комментарий админа")
                .append("submittedAt", Date.from(at))
                .append("decidedAt", Date.from(at))
                .append("closedAt", "REPAID".equals(status) ? Date.from(at.plusSeconds(86_400)) : null),
                "credit_history");
    }

    static Map<String, Object> phone(String phone) {
        return Map.of("phone", phone);
    }

    static Map<String, Object> name(String lastName, String firstName) {
        return Map.of("lastName", lastName, "firstName", firstName);
    }

    static Map<String, Object> both(String phone, String lastName, String firstName) {
        return Map.of("phone", phone, "lastName", lastName, "firstName", firstName);
    }

    // --- helpers ---

    record Provider(UUID id, UUID keyId, String apiKey) {
    }

    Provider newProvider(String name, int perMinute, int perDay) {
        JsonNode created = json(http.post().uri("/api/admin/providers").header("X-Admin-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "sandbox", false, "requestsPerMinute", perMinute, "requestsPerDay", perDay))
                .exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                        .body(new String(res.getBody().readAllBytes())), true));
        UUID id = UUID.fromString(created.get("id").asString());
        JsonNode key = json(admin("/api/admin/providers/" + id + "/keys"));
        return new Provider(id, UUID.fromString(key.get("keyId").asString()), key.get("apiKey").asString());
    }

    ResponseEntity<String> admin(String path) {
        return http.post().uri(path).header("X-Admin-Key", ADMIN_KEY)
                .exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                        .body(new String(res.getBody().readAllBytes())), true);
    }

    ResponseEntity<String> report(String apiKey, Map<String, Object> body, String idempotencyKey) {
        return http.post().uri("/api/v1/credit-reports")
                .headers(headers -> {
                    if (apiKey != null) {
                        headers.set("X-Api-Key", apiKey);
                    }
                    if (idempotencyKey != null) {
                        headers.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> ResponseEntity.status(res.getStatusCode()).headers(res.getHeaders())
                        .body(new String(res.getBody().readAllBytes())), true);
    }

    ResponseEntity<String> get(String path) {
        return http.get().uri(path).exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                .body(new String(res.getBody().readAllBytes())), true);
    }

    long journal(UUID providerId) {
        Long count = jdbc.queryForObject("select count(*) from report_requests where provider_id = ?", Long.class,
                providerId);
        return count == null ? 0 : count;
    }

    JsonNode json(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody());
    }

    String code(ResponseEntity<String> response) {
        return json(response).get("code").asString();
    }
}
