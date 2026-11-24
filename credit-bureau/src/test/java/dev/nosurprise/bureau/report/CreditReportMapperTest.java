package dev.nosurprise.bureau.report;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreditReportMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    @Test
    @DisplayName("найден: количество, статусы, по займу статус, сумма строкой и даты")
    void found() {
        StoredHistory history = new StoredHistory(List.of(
                new StoredHistory.StoredLoan("REPAID", new BigDecimal("300"), NOW, NOW, NOW,
                        LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), 2),
                new StoredHistory.StoredLoan("ACTIVE", new BigDecimal("800.5"), NOW, NOW, null,
                        LocalDate.of(2026, 11, 25), null, 0)));
        UUID reportId = UUID.randomUUID();

        CreditReportResponse response = CreditReportMapper.found(reportId, SearchQuery.SearchBy.PHONE, history, NOW);

        assertThat(response.found()).isTrue();
        assertThat(response.reportId()).isEqualTo(reportId);
        assertThat(response.matchedBy()).isEqualTo("PHONE");
        assertThat(response.totalLoans()).isEqualTo(2);
        assertThat(response.loansByStatus()).containsEntry("REPAID", 1).containsEntry("ACTIVE", 1);
        assertThat(response.loans()).extracting(CreditReportResponse.Loan::amount).containsExactly("300.00", "800.50");
        assertThat(response.loans().get(1).closedAt()).isNull();
        assertThat(response.loans()).allSatisfy(loan -> assertThat(loan.currency()).isEqualTo("BYN"));
        assertThat(response.loans().get(0).overdueDays()).isEqualTo(2);
        assertThat(response.loans().get(0).paidAt()).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    @DisplayName("просроченный: дни просрочки считаются от даты возврата на дату ответа по Минску")
    void overdueDaysAreCountedAtReportTime() {
        StoredHistory history = new StoredHistory(List.of(
                new StoredHistory.StoredLoan("OVERDUE", new BigDecimal("500"), NOW, NOW, null,
                        LocalDate.of(2026, 11, 8), null, 1)));

        CreditReportResponse response = CreditReportMapper.found(UUID.randomUUID(), SearchQuery.SearchBy.PHONE,
                history, NOW);

        assertThat(response.loansByStatus()).containsEntry("OVERDUE", 1);
        assertThat(response.loans().get(0).overdueDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("не найден: только reportId, found=false и время")
    void notFound() {
        CreditReportResponse response = CreditReportMapper.notFound(UUID.randomUUID(), NOW);

        assertThat(response.found()).isFalse();
        assertThat(response.matchedBy()).isNull();
        assertThat(response.loans()).isNull();
    }

    @Test
    @DisplayName("займ из bson монолита: Decimal128 и Date читаются, лишние поля игнорируются")
    void readsMonolithDocument() {
        Document loan = new Document("status", "REPAID")
                .append("loan", new Document("amount", new Decimal128(new BigDecimal("500.00"))).append("total", "x"))
                .append("submittedAt", Date.from(NOW))
                .append("decidedAt", Date.from(NOW))
                .append("closedAt", null)
                .append("decisionComment", "внутреннее");

        StoredHistory.StoredLoan parsed = CreditHistoryReader.loan(loan);

        assertThat(parsed.status()).isEqualTo("REPAID");
        assertThat(parsed.amount()).isEqualByComparingTo("500.00");
        assertThat(parsed.submittedAt()).isEqualTo(NOW);
        assertThat(parsed.closedAt()).isNull();
    }

    @Test
    @DisplayName("песочница: по тем же правилам поиска; однофамильцы находятся вдвоём")
    void sandbox() {
        assertThat(SandboxReports.find(SearchQuery.of(SandboxReports.GOOD_PAYER_PHONE, null, null, null))).hasSize(1);
        assertThat(SandboxReports.find(SearchQuery.of(SandboxReports.GOOD_PAYER_PHONE, "Иванов", "Иван", null)))
                .hasSize(1);
        assertThat(SandboxReports.find(SearchQuery.of(SandboxReports.GOOD_PAYER_PHONE, "Петров", "Петр", null)))
                .isEmpty();
        assertThat(SandboxReports.find(SearchQuery.of(null, "Тестов", "Тест", null))).hasSize(2);
        assertThat(SandboxReports.find(SearchQuery.of("+375291111111", null, null, null))).isEmpty();
    }
}
