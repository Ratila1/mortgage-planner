package dev.nosurprise.integration;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import dev.nosurprise.integration.support.LoanItSupport;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.scoring.ScoringCompleted;
import dev.nosurprise.loan.scoring.ScoringResultService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Подпись при подаче: маркер, авто-активация после договора и одно уведомление «Заём выдан». */
@DisplayName("Микрозайм: подпись при подаче")
class SignedAtSubmitFlowIntegrationTest extends LoanItSupport {

    @Autowired
    private LoanApplicationRepository loanApplications;

    @Autowired
    private ScoringResultService scoringResults;

    @Test
    @DisplayName("без подтверждённого телефона submit возвращает PHONE_NOT_VERIFIED и заявки не создаёт")
    void submitRequiresVerifiedPhone() {
        Client client = new Client(auth.registerAndVerify("unsigned", PASSWORD), "+375291234567");
        assertThat(api.putJson("/api/me/profile", profileJson(client.phone(), "2000.00"), client.token())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(uploadPassport(client.token(), PNG).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> response = submit(client);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(response, objectMapper)).isEqualTo("PHONE_NOT_VERIFIED");
        Integer applications = jdbc.queryForObject("select count(*) from loan_applications where user_id = ?::uuid",
                Integer.class, client.id());
        assertThat(applications).isZero();
    }

    @Test
    @DisplayName("подтверждённая заявка становится ACTIVE после договора, подпись хранит номер из снимка")
    void signedAtSubmitActivatesAfterContract() throws Exception {
        Client client = readyClient("signed-flow");
        JsonNode submitted = submitOk(client);
        String applicationId = id(submitted);
        assertThat(submitted.get("signedAtSubmit").asBoolean()).isTrue();

        // Номер в профиле меняется после подачи — подпись и договор должны нести номер из снимка.
        String changedPhone = changedPhone(client.phone());
        assertThat(api.putJson("/api/me/profile", profileJson(changedPhone, "2000.00"), client.token())
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        completeScoring(applicationId);
        String admin = adminToken();
        JsonNode card = json(api.get("/api/admin/loan-applications/" + applicationId, admin));
        ResponseEntity<String> approval = api.postJson("/api/admin/loan-applications/" + applicationId + "/approve",
                "{\"version\":" + card.get("version").asLong() + "}", admin);
        assertThat(approval.getStatusCode()).as(approval.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode approved = json(approval);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        JsonNode contract = awaitContract(client, applicationId);
        ResponseEntity<byte[]> pdf = api.getBytes(contract.get("downloadUrl").asString(), client.token());
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        String contractText = pdfText(pdf.getBody());
        assertThat(contractText).contains("Телефон: " + client.phone()).doesNotContain(changedPhone);

        await().atMost(ASYNC).until(() -> "ACTIVE".equals(getApplication(client, applicationId)
                .get("status").asString()));
        assertThat(jdbc.queryForObject("select count(*) from loan_signatures where application_id = ?::uuid",
                Integer.class, applicationId)).isEqualTo(1);
        Map<String, Object> signature = jdbc.queryForMap(
                "select phone, document_id, document_sha256 from loan_signatures where application_id = ?::uuid",
                applicationId);
        Map<String, Object> contractRow = jdbc.queryForMap(
                "select id, sha256 from loan_documents where application_id = ?::uuid and type = 'CONTRACT'",
                applicationId);
        assertThat(signature.get("phone")).isEqualTo(client.phone());
        assertThat(signature.get("document_id")).isEqualTo(contractRow.get("id"));
        assertThat(signature.get("document_sha256")).isEqualTo(contractRow.get("sha256"));
        await().atMost(ASYNC).until(() -> notifications(client.id(), "LOAN_ISSUED") == 1);
        assertThat(notifications(client.id(), "LOAN_APPROVED")).isZero();
        assertThat(notifications(client.id(), "LOAN_SIGNED")).isZero();

        await().atMost(ASYNC).until(() -> {
            Document history = creditHistory(applicationId);
            return history != null && "ACTIVE".equals(history.getString("status"));
        });
        List<String> timeline = creditHistory(applicationId).getList("timeline", Document.class).stream()
                .map(entry -> entry.getString("status")).toList();
        assertThat(timeline).containsExactly("SUBMITTED", "APPROVED", "ACTIVE");
    }

    private static String changedPhone(String phone) {
        return phone.substring(0, phone.length() - 1) + (phone.endsWith("9") ? "8" : "9");
    }

    private static String pdfText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page));
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    /** credit-policy отвечает асинхронно; в тесте результат подставляем напрямую. */
    private void completeScoring(String applicationId) {
        UUID id = UUID.fromString(applicationId);
        UUID requestId = loanApplications.findById(id).orElseThrow().getScoringRequestId();
        scoringResults.apply(new ScoringCompleted(UUID.randomUUID(), Instant.now(), requestId, id, "COMPLETED",
                new BigDecimal("0.05"), 40, "B2", new BigDecimal("0.0194"), "RECOMMEND", List.of(), 1));
    }
}
