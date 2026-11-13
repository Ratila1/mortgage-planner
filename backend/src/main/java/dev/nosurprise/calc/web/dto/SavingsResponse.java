package dev.nosurprise.calc.web.dto;

/**
 * «Сэкономили» (§5). overpaymentSaved — строка.
 */
public record SavingsResponse(
        String overpaymentSaved,
        int monthsSaved
) {
}
