package dev.nosurprise.policy.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Запрос скоринга от монолита: routing key loan.scoring.requested (CREDIT_POLICY.md §8.1).
 * Все данные для расчёта приходят в событии — в базу монолита сервис не ходит.
 */
public record ScoringRequested(UUID eventId, Instant occurredAt, UUID requestId, UUID applicationId, UUID userId,
                               Applicant applicant, Loan loan, History history) {

    public static final String ROUTING_KEY = "loan.scoring.requested";

    /** sex: MALE | FEMALE; country: ISO-код страны (BY). Поля могут быть null у старых профилей. */
    public record Applicant(String lastName, String firstName, String middleName, String phone, LocalDate birthDate,
                            String sex, String country, String city) {
    }

    /**
     * Цель займа — сам тип займа (CREDIT_POLICY.md §3.2): его название и балл цели 0–1 из монолита
     * (loan_types.purpose_score). Балл приходит в событии и сохраняется в input — расчёт воспроизводим.
     */
    public record Loan(UUID loanTypeId, String loanTypeName, BigDecimal purposeScore, BigDecimal amount, int termDays,
                       BigDecimal total, BigDecimal monthlyIncome) {
    }

    /** Сводка по прошлым заявкам клиента; текущая заявка в неё не входит. */
    public record History(int signedLoans, int repaidOnTime, int repaidLate, int maxOverdueDays, boolean overdueNow,
                          int rejectedLast12Months) {

        public static final History EMPTY = new History(0, 0, 0, 0, false, 0);
    }
}
