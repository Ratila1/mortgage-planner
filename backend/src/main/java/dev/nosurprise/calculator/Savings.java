package dev.nosurprise.calculator;

import java.math.BigDecimal;

/**
 * «Сэкономили» (§5): сравнение с расчётом без досрочных погашений.
 * overpaymentSaved — на сколько меньше переплата; monthsSaved — на сколько месяцев меньше срок.
 */
public record Savings(
        BigDecimal overpaymentSaved,
        int monthsSaved
) {
}
