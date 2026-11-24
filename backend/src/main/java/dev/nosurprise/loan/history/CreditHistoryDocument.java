package dev.nosurprise.loan.history;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Копия займа в Mongo со всеми состояниями и проколами (MICROLOANS.md §3.14, §4.9).
 * Один документ на заявку, _id = applicationId. Документ всегда собирается целиком из Postgres;
 * lastEventId — версия: заменяем документ только более свежим. Персональных данных нет, только userId.
 * dueDate и paidAt — даты ISO-строкой (yyyy-MM-dd): без часового пояса, одинаково читаются бюро.
 * overdueDays — для погашенного по фактической дате оплаты; для OVERDUE — на момент синхронизации
 * (актуальное значение читатель считает от dueDate). Схема 2 — поля просрочки (CREDIT_POLICY.md §5.3).
 */
@Document("credit_history")
public record CreditHistoryDocument(
        @Id UUID id,
        UUID userId,
        int schemaVersion,
        long lastEventId,
        String status,
        Loan loan,
        BigDecimal monthlyIncome,
        BigDecimal debtLoadPercent,
        Instant submittedAt,
        Instant decidedAt,
        UUID decidedBy,
        String decisionComment,
        Instant closedAt,
        String dueDate,
        String paidAt,
        Integer overdueDays,
        List<TimelineEntry> timeline,
        List<Incident> incidents,
        Instant syncedAt) {

    public static final int SCHEMA_VERSION = 2;

    public record Loan(UUID typeId, String typeName, BigDecimal amount, int termDays, BigDecimal dailyRate,
                       BigDecimal modifier, BigDecimal interest, BigDecimal total) {
    }

    public record TimelineEntry(String status, Instant at, UUID actorId) {
    }

    public record Incident(String type, Instant at, String details) {
    }
}
