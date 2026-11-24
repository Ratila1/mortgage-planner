package dev.nosurprise.loan.scoring;

import dev.nosurprise.common.outbox.OutboxMessage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Запрос скоринга в credit-policy: loan.scoring.requested (CREDIT_POLICY.md §8.1). В отличие от остальных
 * событий займа, несёт персональные данные: сервис скоринга не ходит в нашу базу, всё нужное — в событии.
 */
public record ScoringRequested(UUID eventId, Instant occurredAt, UUID requestId, UUID applicationId, UUID userId,
                               Applicant applicant, Loan loan, History history) implements OutboxMessage {

    public record Applicant(String lastName, String firstName, String middleName, String phone, LocalDate birthDate,
                            String sex, String country, String city) {
    }

    /** Цель займа — сам тип займа: его название и балл цели (loan_types.purpose_score, CREDIT_POLICY.md §3.2). */
    public record Loan(UUID loanTypeId, String loanTypeName, BigDecimal purposeScore, BigDecimal amount, int termDays,
                       BigDecimal total, BigDecimal monthlyIncome) {
    }

    /** Сводка по прошлым заявкам клиента; текущая в неё не входит. */
    public record History(int signedLoans, int repaidOnTime, int repaidLate, int maxOverdueDays, boolean overdueNow,
                          int rejectedLast12Months) {
    }
}
