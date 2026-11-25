package dev.nosurprise.bureau.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ответ бюро (CREDIT_BUREAU.md §7.1). Наружу — только количество займов, количество по статусам и по каждому
 * займу статус, сумма и даты. ФИО и телефон не возвращаются: банк прислал их сам, а если прислал только телефон —
 * ФИО раскрывать нельзя. Заёмщик не найден → found=false без остальных полей (null не сериализуются).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Кредитная история заёмщика")
public record CreditReportResponse(
        @Schema(description = "Id ответа в журнале бюро — указывать при спорах и сверке счетов")
        UUID reportId,
        @Schema(description = "Найден ли заёмщик")
        boolean found,
        @Schema(description = "По каким параметрам искали: PHONE, NAME, PHONE_AND_NAME")
        String matchedBy,
        @Schema(description = "Всего займов")
        Integer totalLoans,
        @Schema(description = "Количество займов по статусам", example = "{\"REPAID\": 2, \"ACTIVE\": 1}")
        Map<String, Integer> loansByStatus,
        @Schema(description = "Займы, старые первыми")
        List<Loan> loans,
        Instant generatedAt) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "Займ")
    public record Loan(
            @Schema(description = "ACTIVE — действует, OVERDUE — просрочен, REPAID — погашен", example = "REPAID")
            String status,
            @Schema(description = "Сумма займа, строкой без потери точности", example = "500.00")
            String amount,
            @Schema(example = "BYN")
            String currency,
            @Schema(description = "Дата подачи заявки")
            Instant submittedAt,
            @Schema(description = "Дата одобрения")
            Instant decidedAt,
            @Schema(description = "Когда займ закрыт в системе; null — займ действует")
            Instant closedAt,
            @Schema(description = "Дата возврата по договору; null — у займов до появления даты возврата",
                    example = "2026-11-30")
            LocalDate dueDate,
            @Schema(description = "Фактическая дата оплаты; null — не погашен", example = "2026-11-29")
            LocalDate paidAt,
            @Schema(description = "Дней просрочки: у погашенного — итог, у просроченного — на сегодня", example = "0")
            Integer overdueDays) {
    }
}
