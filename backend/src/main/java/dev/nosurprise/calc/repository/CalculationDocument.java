package dev.nosurprise.calc.repository;

import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.LoanSummary;
import dev.nosurprise.calculator.Savings;
import dev.nosurprise.calculator.ScheduleRow;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Документ коллекции {@code calculations} (§6.2): один документ на расчёт.
 * Деньги — BigDecimal → Decimal128 (representation.big-decimal=decimal128).
 * Индексы создаются программно в dev.nosurprise.calc.config.CalculationIndexes — аннотации не используются.
 */
@Document("calculations")
public record CalculationDocument(
        @Id String id,
        String paramsHash,
        /** null для анонимных расчётов. */
        String userId,
        LoanParams params,
        LoanSummary summary,
        Savings savings,
        List<ScheduleRow> schedule,
        Instant createdAt,
        /** TTL-индекс; null для расчётов пользователя (живут бессрочно). */
        Instant expireAt
) {
}
