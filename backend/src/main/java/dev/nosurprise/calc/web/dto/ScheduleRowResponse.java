package dev.nosurprise.calc.web.dto;

/**
 * Строка графика (§5). Все суммы — строки.
 */
public record ScheduleRowResponse(
        int month,
        String payment,
        String principal,
        String interest,
        String earlyRepayment,
        String balance
) {
}
