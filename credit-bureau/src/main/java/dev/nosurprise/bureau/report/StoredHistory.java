package dev.nosurprise.bureau.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * То, что бюро берёт из кредитной истории: только поля, которые можно отдать банку (CREDIT_BUREAU.md §7.1).
 * Остальное содержимое документов (ПДН, зарплата, комментарии админа, проколы) сюда даже не читается.
 */
public record StoredHistory(List<StoredLoan> loans) {

    /** overdueDays — как записал монолит (для OVERDUE — на момент синхронизации); dueDate/paidAt — с V17. */
    public record StoredLoan(String status, BigDecimal amount, Instant submittedAt, Instant decidedAt,
                             Instant closedAt, LocalDate dueDate, LocalDate paidAt, Integer overdueDays) {
    }
}
