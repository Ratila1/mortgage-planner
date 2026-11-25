package dev.nosurprise.integration;

import dev.nosurprise.integration.support.LoanItSupport;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Полный путь микрозайма (MICROLOANS.md §3.3): подача → одобрение → договор → уведомление → подпись → ACTIVE
 * → погашение, и копия всех состояний в Mongo (§3.14).
 */
@DisplayName("Микрозайм: полный путь от подачи до погашения")
class LoanFlowIntegrationTest extends LoanItSupport {

    @Test
    @DisplayName("подача → одобрение → PDF → уведомление → подпись → ACTIVE → погашение; Mongo повторяет все состояния")
    void fullFlow() {
        Client client = readyClient("loan-flow");
        String admin = adminToken();

        JsonNode submitted = submitOk(client);
        String applicationId = id(submitted);
        assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("total").asString()).isEqualTo("570.00");
        assertThat(submitted.get("modifier").asString()).isEqualTo("20.00");
        assertThat(submitted.get("debtLoadPercent").asString()).isEqualTo("28.50");

        ResponseEntity<String> list = api.get("/api/admin/loan-applications?status=SUBMITTED&size=100", admin);
        assertThat(list.getBody()).contains(applicationId);

        JsonNode approved = approve(admin, submitted);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("offerExpiresAt").isNull()).isFalse();

        JsonNode contract = awaitContract(client, applicationId);
        ResponseEntity<byte[]> pdf = api.getBytes(contract.get("downloadUrl").asString(), client.token());
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(new String(pdf.getBody(), 0, 5)).isEqualTo("%PDF-");

        JsonNode active = sign(client, applicationId);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from loan_signatures where application_id = ?::uuid",
                Integer.class, applicationId)).isEqualTo(1);
        await().atMost(ASYNC).until(() -> notifications(client.id(), "LOAN_ISSUED") == 1);
        assertThat(notifications(client.id(), "LOAN_APPROVED")).isZero();
        assertThat(notifications(client.id(), "LOAN_SIGNED")).isZero();

        // Активный заём блокирует новую заявку (§3.8).
        ResponseEntity<String> second = submit(client);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(second, objectMapper)).isEqualTo("ACTIVE_APPLICATION_EXISTS");

        JsonNode card = json(api.get("/api/admin/loan-applications/" + applicationId, admin));
        ResponseEntity<String> repaid = api.postJson("/api/admin/loan-applications/" + applicationId + "/repaid",
                "{\"version\":" + card.get("version").asLong() + "}", admin);
        assertThat(repaid.getStatusCode()).as(repaid.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(repaid).get("status").asString()).isEqualTo("REPAID");

        await().atMost(ASYNC).until(() -> {
            Document history = creditHistory(applicationId);
            return history != null && "REPAID".equals(history.getString("status"));
        });
        Document history = creditHistory(applicationId);
        List<String> timeline = history.getList("timeline", Document.class).stream()
                .map(entry -> entry.getString("status")).toList();
        assertThat(timeline).containsExactly("SUBMITTED", "APPROVED", "ACTIVE", "REPAID");
        await().atMost(ASYNC).until(() -> {
            Long synced = jdbc.queryForObject("select credit_history_synced_event_id from loan_applications "
                    + "where id = ?::uuid", Long.class, applicationId);
            Long last = jdbc.queryForObject("select max(id) from loan_events where application_id = ?::uuid",
                    Long.class, applicationId);
            return synced != null && synced.equals(last);
        });

        // После погашения можно подать новую заявку.
        assertThat(submit(client).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode creditHistory = json(api.get("/api/admin/users/" + client.id() + "/credit-history", admin));
        assertThat(creditHistory.get("summary").get("totalLoans").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("отказ: клиент видит причину в ЛК, уведомление с причиной, в Mongo статус REJECTED")
    void rejection() {
        Client client = readyClient("loan-reject");
        JsonNode submitted = submitOk(client);

        reject(adminToken(), submitted, "Недостаточный доход");

        JsonNode mine = getApplication(client, id(submitted));
        assertThat(mine.get("status").asString()).isEqualTo("REJECTED");
        assertThat(mine.get("rejectionReason").asString()).isEqualTo("Недостаточный доход");
        await().atMost(ASYNC).until(() -> notifications(client.id(), "LOAN_REJECTED") == 1);
        await().atMost(ASYNC).until(() -> {
            Document history = creditHistory(id(submitted));
            return history != null && "REJECTED".equals(history.getString("status"));
        });
    }
}
