package dev.nosurprise.integration;

import dev.nosurprise.integration.support.LoanItSupport;
import dev.nosurprise.loan.jobs.LoanOverdueJob;
import dev.nosurprise.loan.scoring.ScoringResultService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Скоринг и просрочка в монолите (CREDIT_POLICY.md, часть A). Ответ credit-policy имитируется. */
@DisplayName("Микрозайм: скоринг и просрочка")
class ScoringAndOverdueIntegrationTest extends LoanItSupport {

    private static final ZoneId MINSK = ZoneId.of("Europe/Minsk");

    @Autowired
    private LoanOverdueJob overdueJob;

    @Test
    @DisplayName("подача пишет запрос скоринга с данными профиля; без результата одобрить нельзя — 409 SCORING_REQUIRED")
    void approvalRequiresScoring() {
        Client client = readyClient("score-gate");
        JsonNode submitted = submitOk(client);
        String applicationId = id(submitted);

        String payload = jdbc.queryForObject("select payload::text from outbox_events "
                + "where event_type = 'loan.scoring.requested' and aggregate_id = ?", String.class, applicationId);
        JsonNode request = objectMapper.readTree(payload);
        assertThat(request.get("applicant").get("birthDate").asString()).isEqualTo("1990-05-10");
        assertThat(request.get("applicant").get("city").asString()).isEqualTo("Минск");
        assertThat(request.get("loan").get("loanTypeName").asString()).isNotBlank();
        assertThat(request.get("loan").get("purposeScore").decimalValue()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(request.get("history").get("signedLoans").asInt()).isZero();

        String admin = adminToken();
        JsonNode card = adminCard(admin, applicationId);
        assertThat(card.get("scoring").get("status").asString()).isEqualTo("PENDING");
        ResponseEntity<String> response = api.postJson("/api/admin/loan-applications/" + applicationId + "/approve",
                "{\"version\":" + card.get("version").asLong() + "}", admin);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(response, objectMapper)).isEqualTo("SCORING_REQUIRED");

        completeScoring(applicationId, "RECOMMEND");
        JsonNode scored = adminCard(admin, applicationId);
        assertThat(scored.get("scoring").get("ratingClass").asString()).isEqualTo("B2");
        assertThat(scored.get("scoring").get("pdPercent").asString()).isEqualTo("1.94");
        response = api.postJson("/api/admin/loan-applications/" + applicationId + "/approve",
                "{\"version\":" + scored.get("version").asLong() + "}", admin);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AUTO_REJECT → REJECTED системой с общей причиной и уведомлением; повтор ответа ничего не меняет")
    void autoReject() {
        Client client = readyClient("score-reject");
        String applicationId = id(submitOk(client));

        completeScoring(applicationId, "AUTO_REJECT");
        completeScoring(applicationId, "AUTO_REJECT");

        JsonNode application = getApplication(client, applicationId);
        assertThat(application.get("status").asString()).isEqualTo("REJECTED");
        assertThat(application.get("rejectionReason").asString()).isEqualTo(ScoringResultService.AUTO_REJECT_REASON);
        assertThat(loanEvents(applicationId, "STATUS_CHANGED")).isEqualTo(2);
        await().atMost(ASYNC).until(() -> notifications(client.id(), "LOAN_REJECTED") == 1);
    }

    @Test
    @DisplayName("повторный запрос скоринга: ответ на прежний запрос игнорируется")
    void staleResultIsIgnoredAfterRerequest() {
        Client client = readyClient("score-stale");
        String applicationId = id(submitOk(client));
        UUID firstRequest = loanApplications.findById(UUID.fromString(applicationId)).orElseThrow()
                .getScoringRequestId();

        String admin = adminToken();
        ResponseEntity<String> again = api.postEmpty("/api/admin/loan-applications/" + applicationId + "/scoring",
                admin);
        assertThat(again.getStatusCode()).as(again.getBody()).isEqualTo(HttpStatus.OK);
        UUID secondRequest = loanApplications.findById(UUID.fromString(applicationId)).orElseThrow()
                .getScoringRequestId();
        assertThat(secondRequest).isNotEqualTo(firstRequest);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'loan.scoring.requested' "
                + "and aggregate_id = ?", Integer.class, applicationId)).isEqualTo(2);

        scoringResults.apply(new dev.nosurprise.loan.scoring.ScoringCompleted(UUID.randomUUID(),
                java.time.Instant.now(), firstRequest, UUID.fromString(applicationId), "COMPLETED",
                new java.math.BigDecimal("0.1000"), 40, "E3", new java.math.BigDecimal("0.700000"), "AUTO_REJECT",
                java.util.List.of(), 1));
        assertThat(getApplication(client, applicationId).get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(adminCard(admin, applicationId).get("scoring").get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("просрочка: ACTIVE → OVERDUE ночью, напоминание на 3 день один раз, погашение с фактической датой")
    void overdueLifecycle() {
        Client client = readyClient("overdue");
        JsonNode submitted = submitOk(client);
        String applicationId = id(submitted);
        String admin = adminToken();
        approve(admin, submitted);
        awaitContract(client, applicationId);
        JsonNode signed = sign(client, applicationId);
        assertThat(signed.get("dueDate").asString())
                .isEqualTo(LocalDate.now(MINSK).plusDays(10).toString());

        // Срок вышел три дня назад; дату подачи тоже сдвигаем, чтобы оплату после срока можно было отметить.
        LocalDate due = LocalDate.now(MINSK).minusDays(3);
        jdbc.update("update loan_applications set due_date = ?, created_at = ?::timestamptz where id = ?::uuid",
                due, due.minusDays(10) + "T00:00:00Z", UUID.fromString(applicationId));
        assertThat(overdueJob.markOverdue()).isEqualTo(1);
        assertThat(overdueJob.markOverdue()).isZero();
        assertThat(overdueJob.sendReminders()).isEqualTo(1);
        assertThat(overdueJob.sendReminders()).isZero();

        JsonNode overdue = getApplication(client, applicationId);
        assertThat(overdue.get("status").asString()).isEqualTo("OVERDUE");
        assertThat(overdue.get("overdueDays").asInt()).isEqualTo(3);
        await().atMost(ASYNC).until(() -> notifications(client.id(), "LOAN_OVERDUE") == 2);

        // Должник не подаст новую заявку.
        ResponseEntity<String> second = submit(client);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(second, objectMapper)).isEqualTo("ACTIVE_APPLICATION_EXISTS");

        // Оплатил через день после срока, админ отметил сегодня.
        JsonNode card = adminCard(admin, applicationId);
        ResponseEntity<String> repaid = api.postJson("/api/admin/loan-applications/" + applicationId + "/repaid",
                "{\"version\":" + card.get("version").asLong() + ",\"paidAt\":\"" + due.plusDays(1) + "\"}", admin);
        assertThat(repaid.getStatusCode()).as(repaid.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(repaid).get("overdueDays").asInt()).isEqualTo(1);

        // Даты подставлены вручную — пересобираем историю синхронно, чтобы проверить итог, а не очередность outbox.
        assertThat(api.postEmpty("/api/admin/credit-history/rebuild", admin).getStatusCode().is2xxSuccessful()).isTrue();
        await().atMost(ASYNC).until(() -> {
            Document history = creditHistory(applicationId);
            return history != null && "REPAID".equals(history.getString("status"));
        });
        Document history = creditHistory(applicationId);
        assertThat(history.getString("dueDate")).isEqualTo(due.toString());
        assertThat(history.getInteger("overdueDays")).isEqualTo(1);
        assertThat(history.getList("timeline", Document.class).stream().map(e -> e.getString("status")).toList())
                .containsExactly("SUBMITTED", "APPROVED", "ACTIVE", "OVERDUE", "REPAID");
    }

    @Test
    @DisplayName("дата оплаты в будущем → 400")
    void paidAtInFuture() {
        Client client = readyClient("overdue-future");
        JsonNode submitted = submitOk(client);
        String applicationId = id(submitted);
        String admin = adminToken();
        approve(admin, submitted);
        awaitContract(client, applicationId);
        sign(client, applicationId);

        JsonNode card = adminCard(admin, applicationId);
        ResponseEntity<String> repaid = api.postJson("/api/admin/loan-applications/" + applicationId + "/repaid",
                "{\"version\":" + card.get("version").asLong() + ",\"paidAt\":\""
                        + LocalDate.now(MINSK).plusDays(2) + "\"}", admin);
        assertThat(repaid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(repaid, objectMapper)).isEqualTo("VALIDATION_FAILED");
    }
}
