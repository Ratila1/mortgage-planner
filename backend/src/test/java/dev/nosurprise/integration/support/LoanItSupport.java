package dev.nosurprise.integration.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Общие шаги integration-тестов микрозаймов: клиент с профилем, подтверждённым телефоном и паспортом,
 * подача, решения админа, ожидание асинхронных шагов (outbox → RabbitMQ → consumer-ы).
 */
@Import(RecordingSmsSender.Config.class)
public abstract class LoanItSupport extends AbstractIntegrationTest {

    /** Seed V14: «До зарплаты», 100–1000 BYN, 7–14 дней, 1 % в день, модификатор 20. */
    public static final String SALARY_TYPE_ID = "a2222222-2222-2222-2222-222222222222";
    public static final String PASSWORD = "Strong-pass-123";
    public static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    protected static final Duration ASYNC = Duration.ofSeconds(30);

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected MongoTemplate mongo;

    @Autowired
    protected RecordingSmsSender sms;

    protected AuthFixture auth;

    @BeforeEach
    void loanSupportSetUp() {
        auth = new AuthFixture(api, jdbc, redis, objectMapper);
    }

    /** Клиент: зарегистрирован, профиль заполнен, телефон подтверждён, паспорт загружен. */
    protected Client readyClient(String prefix) {
        AuthFixture.Account account = auth.registerAndVerify(prefix, PASSWORD);
        String phone = "+37529" + String.format("%07d", Math.abs(account.id().hashCode()) % 10_000_000);
        Client client = new Client(account, phone);
        assertThat(api.putJson("/api/me/profile", profileJson(phone, "2000.00"), client.token()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        verifyPhone(client);
        assertThat(uploadPassport(client.token(), PNG).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return client;
    }

    protected void verifyPhone(Client client) {
        clearSmsCooldown(client);
        assertThat(api.postEmpty("/api/me/phone/verification", client.token()).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        String code = sms.lastCode(client.phone());
        assertThat(api.postJson("/api/me/phone/verification/confirm", "{\"code\":\"" + code + "\"}", client.token())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Пауза 60 с между SMS — бизнес-правило; в тестах её снимаем, чтобы не ждать. */
    protected void clearSmsCooldown(Client client) {
        redis.delete("sms-cooldown:" + client.id());
    }

    protected String adminToken() {
        return auth.loginExisting(ADMIN_EMAIL, ADMIN_PASSWORD).accessToken();
    }

    protected static String profileJson(String phone, String income) {
        return "{\"lastName\":\"Иванов\",\"firstName\":\"Иван\",\"middleName\":\"Иванович\",\"phone\":\"" + phone
                + "\",\"monthlyIncome\":\"" + income + "\"}";
    }

    protected static String submitJson(String phone, String amount, int termDays) {
        return "{\"loanTypeId\":\"" + SALARY_TYPE_ID + "\",\"amount\":\"" + amount + "\",\"termDays\":" + termDays
                + ",\"profile\":" + profileJson(phone, "2000.00") + "}";
    }

    protected ResponseEntity<String> submit(Client client) {
        return api.postJson("/api/loans/applications", submitJson(client.phone(), "500.00", 10), client.token());
    }

    protected JsonNode submitOk(Client client) {
        ResponseEntity<String> response = submit(client);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return json(response);
    }

    protected JsonNode approve(String adminToken, JsonNode application) {
        ResponseEntity<String> response = api.postJson("/api/admin/loan-applications/" + id(application) + "/approve",
                "{\"version\":" + application.get("version").asLong() + "}", adminToken);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return json(response);
    }

    protected JsonNode reject(String adminToken, JsonNode application, String reason) {
        ResponseEntity<String> response = api.postJson("/api/admin/loan-applications/" + id(application) + "/reject",
                "{\"reason\":\"" + reason + "\",\"version\":" + application.get("version").asLong() + "}", adminToken);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return json(response);
    }

    protected JsonNode getApplication(Client client, String applicationId) {
        ResponseEntity<String> response = api.get("/api/loans/applications/" + applicationId, client.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response);
    }

    /** Договор генерируется асинхронно после одобрения. */
    protected JsonNode awaitContract(Client client, String applicationId) {
        await().atMost(ASYNC).until(() -> getApplication(client, applicationId).get("documents").size() == 1);
        return getApplication(client, applicationId).get("documents").get(0);
    }

    protected JsonNode sign(Client client, String applicationId) {
        clearSmsCooldown(client);
        ResponseEntity<String> requested = api.postEmpty("/api/loans/applications/" + applicationId + "/signature",
                client.token());
        assertThat(requested.getStatusCode()).as(requested.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<String> confirmed = api.postJson("/api/loans/applications/" + applicationId
                + "/signature/confirm", "{\"code\":\"" + sms.lastCode(client.phone()) + "\"}", client.token());
        assertThat(confirmed.getStatusCode()).as(confirmed.getBody()).isEqualTo(HttpStatus.OK);
        return json(confirmed);
    }

    protected ResponseEntity<String> uploadPassport(String token, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "passport.png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        return RestClient.create("http://localhost:" + port).post()
                .uri("/api/me/kyc/passport")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build())
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    protected org.bson.Document creditHistory(String applicationId) {
        return mongo.findById(UUID.fromString(applicationId), org.bson.Document.class, "credit_history");
    }

    protected int notifications(UUID userId, String type) {
        Integer count = jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = ?",
                Integer.class, userId, type);
        return count == null ? 0 : count;
    }

    protected int loanEvents(String applicationId, String type) {
        Integer count = jdbc.queryForObject("select count(*) from loan_events where application_id = ?::uuid "
                + "and type = ?", Integer.class, applicationId, type);
        return count == null ? 0 : count;
    }

    protected JsonNode json(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody());
    }

    protected static String id(JsonNode node) {
        return node.get("id").asString();
    }

    protected static String code(ResponseEntity<String> response, ObjectMapper mapper) {
        return mapper.readTree(response.getBody()).get("code").asString();
    }

    public record Client(AuthFixture.Account account, String phone) {
        public UUID id() {
            return account.id();
        }

        public String token() {
            return account.accessToken();
        }
    }
}
