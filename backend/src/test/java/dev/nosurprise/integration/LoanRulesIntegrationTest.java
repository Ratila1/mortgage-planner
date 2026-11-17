package dev.nosurprise.integration;

import dev.nosurprise.integration.support.ApiClient;
import dev.nosurprise.integration.support.LoanItSupport;
import dev.nosurprise.loan.jobs.KycRetentionJob;
import dev.nosurprise.loan.jobs.OfferExpiryJob;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Бизнес-правила и защиты микрозаймов (MICROLOANS.md §3.8, §3.10, §3.11, §5, §6). */
@DisplayName("Микрозайм: правила, ограничения и защиты")
class LoanRulesIntegrationTest extends LoanItSupport {

    @Autowired
    private OfferExpiryJob expiryJob;

    @Autowired
    private KycRetentionJob retentionJob;

    @Test
    @DisplayName("без фото паспорта → 409 KYC_REQUIRED, и профиль из формы не сохранён (одна транзакция)")
    void submitWithoutPassport() {
        var account = auth.registerAndVerify("loan-nokyc", PASSWORD);
        ResponseEntity<String> response = api.postJson("/api/loans/applications",
                submitJson("+375291111111", "500.00", 10), account.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(response, objectMapper)).isEqualTo("KYC_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from customer_profile where user_id = ?", Integer.class,
                account.id())).isZero();
    }

    @Test
    @DisplayName("сумма вне лимитов типа → 400 LOAN_PARAMS_OUT_OF_RANGE")
    void outOfRange() {
        Client client = readyClient("loan-range");
        ResponseEntity<String> response = api.postJson("/api/loans/applications",
                submitJson(client.phone(), "5000.00", 10), client.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(response, objectMapper)).isEqualTo("LOAN_PARAMS_OUT_OF_RANGE");
    }

    @Test
    @DisplayName("два параллельных «Подать» → одна заявка, второй ответ 409")
    void parallelSubmit() throws Exception {
        Client client = readyClient("loan-parallel");
        Callable<HttpStatus> call = () -> HttpStatus.valueOf(new ApiClient(port).postJson("/api/loans/applications",
                submitJson(client.phone(), "500.00", 10), client.token()).getStatusCode().value());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<HttpStatus>> results = new ArrayList<>();
        try {
            results.add(pool.submit(call));
            results.add(pool.submit(call));
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("select count(*) from loan_applications where user_id = ?", Integer.class,
                client.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("чужая заявка и чужой документ → 404")
    void foreignApplication() {
        Client owner = readyClient("loan-owner");
        Client stranger = readyClient("loan-stranger");
        JsonNode application = submitOk(owner);
        approve(adminToken(), application);
        JsonNode contract = awaitContract(owner, id(application));

        assertThat(api.get("/api/loans/applications/" + id(application), stranger.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.getBytes(contract.get("downloadUrl").asString(), stranger.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.postEmpty("/api/loans/applications/" + id(application) + "/cancel", stranger.token())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("загрузка паспорта: больше 5 МБ → 413, не картинка → 415")
    void uploadLimits() {
        var account = auth.registerAndVerify("loan-upload", PASSWORD);
        // Чуть больше лимита 5 МБ: Tomcat успевает дочитать хвост запроса и вернуть 413, а не оборвать соединение.
        byte[] big = new byte[5 * 1024 * 1024 + 100 * 1024];
        System.arraycopy(PNG, 0, big, 0, PNG.length);

        assertThat(uploadPassport(account.accessToken(), big).getStatusCode().value()).isEqualTo(413);
        assertThat(uploadPassport(account.accessToken(), "%PDF-1.7 not an image".getBytes()).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(uploadPassport(account.accessToken(), PNG).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("подпись без подтверждённого телефона → 409 PHONE_NOT_VERIFIED и прокол в журнале")
    void signWithoutVerifiedPhone() {
        Client client = readyClient("loan-nophone");
        JsonNode application = submitOk(client);
        approve(adminToken(), application);
        awaitContract(client, id(application));
        // Смена номера сбрасывает подтверждение (§3.3).
        api.putJson("/api/me/profile", profileJson("+375297777777", "2000.00"), client.token());

        ResponseEntity<String> response = api.postEmpty("/api/loans/applications/" + id(application) + "/signature",
                client.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(response, objectMapper)).isEqualTo("PHONE_NOT_VERIFIED");
        assertThat(loanEvents(id(application), "SIGN_BLOCKED_PHONE_NOT_VERIFIED")).isEqualTo(1);
    }

    @Test
    @DisplayName("неверный код 3 раза → код сгорает, проколы SIGN_CODE_FAILED ×3 и SIGN_CODE_BURNED попадают в Mongo")
    void wrongCodeBurns() {
        Client client = readyClient("loan-wrongcode");
        JsonNode application = submitOk(client);
        String applicationId = id(application);
        approve(adminToken(), application);
        awaitContract(client, applicationId);
        clearSmsCooldown(client);
        api.postEmpty("/api/loans/applications/" + applicationId + "/signature", client.token());
        String realCode = sms.lastCode(client.phone());
        String wrong = realCode.equals("000000") ? "111111" : "000000";

        for (int attempt = 0; attempt < 3; attempt++) {
            ResponseEntity<String> response = api.postJson("/api/loans/applications/" + applicationId
                    + "/signature/confirm", "{\"code\":\"" + wrong + "\"}", client.token());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<String> afterBurn = api.postJson("/api/loans/applications/" + applicationId
                + "/signature/confirm", "{\"code\":\"" + realCode + "\"}", client.token());

        assertThat(afterBurn.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(loanEvents(applicationId, "SIGN_CODE_FAILED")).isEqualTo(3);
        assertThat(loanEvents(applicationId, "SIGN_CODE_BURNED")).isEqualTo(1);
        await().atMost(ASYNC).until(() -> {
            Document history = creditHistory(applicationId);
            return history != null && history.getList("incidents", Document.class).size() == 4;
        });
    }

    @Test
    @DisplayName("истечение через 5 рабочих дней → EXPIRED, прокол OFFER_NOT_SIGNED, уведомление, Mongo")
    void offerExpiry() {
        Client client = readyClient("loan-expiry");
        JsonNode application = submitOk(client);
        String applicationId = id(application);
        approve(adminToken(), application);
        jdbc.update("update loan_applications set offer_expires_at = now() - interval '1 minute' where id = ?::uuid",
                applicationId);

        expiryJob.expireOffers();

        assertThat(getApplication(client, applicationId).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(loanEvents(applicationId, "OFFER_NOT_SIGNED")).isEqualTo(1);
        await().atMost(ASYNC).until(() -> notifications(client.id(), "LOAN_EXPIRED") == 1);
        await().atMost(ASYNC).until(() -> {
            Document history = creditHistory(applicationId);
            return history != null && "EXPIRED".equals(history.getString("status"));
        });
    }

    @Test
    @DisplayName("решение по устаревшей version → 409 CONCURRENT_MODIFICATION")
    void staleVersion() {
        Client client = readyClient("loan-version");
        JsonNode application = submitOk(client);
        String admin = adminToken();
        approve(admin, application);

        ResponseEntity<String> second = api.postJson("/api/admin/loan-applications/" + id(application) + "/reject",
                "{\"reason\":\"x\",\"version\":" + application.get("version").asLong() + "}", admin);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(second, objectMapper)).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    @DisplayName("фото удаляется через 7 дней после отказа, но не пока на него ссылается живая заявка")
    void kycRetention() {
        Client client = readyClient("loan-retention");
        JsonNode first = submitOk(client);
        reject(adminToken(), first, "нет");
        jdbc.update("update loan_applications set closed_at = now() - interval '8 days' where id = ?::uuid", id(first));
        // Новая заявка ссылается на то же фото — удалять нельзя.
        JsonNode second = submitOk(client);

        retentionJob.purgeExpiredPassports();
        assertThat(passportDeleted(client)).isFalse();

        api.postEmpty("/api/loans/applications/" + id(second) + "/cancel", client.token());
        jdbc.update("update loan_applications set closed_at = now() - interval '8 days' where id = ?::uuid", id(second));
        retentionJob.purgeExpiredPassports();
        assertThat(passportDeleted(client)).isTrue();

        // Без фото подать нельзя.
        ResponseEntity<String> third = submit(client);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(third, objectMapper)).isEqualTo("KYC_REQUIRED");
    }

    @Test
    @DisplayName("блокировка: refresh → 403 USER_BLOCKED, подать заявку нельзя")
    void blockedUser() {
        Client client = readyClient("loan-blocked");
        String admin = adminToken();
        assertThat(api.postEmpty("/api/admin/users/" + client.id() + "/block", admin).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> submit = submit(client);
        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(submit, objectMapper)).isEqualTo("USER_BLOCKED");

        ApiClient fresh = new ApiClient(port);
        ResponseEntity<String> login = fresh.postJson("/api/auth/login",
                "{\"email\":\"" + client.account().email() + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(login, objectMapper)).isEqualTo("USER_BLOCKED");
        await().atMost(ASYNC).until(() -> redis.keys("refresh:" + client.id() + ":*").isEmpty());
    }

    private boolean passportDeleted(Client client) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select bool_and(content is null and deleted_at is not null) "
                + "from kyc_documents where user_id = ?", Boolean.class, client.id()));
    }
}
