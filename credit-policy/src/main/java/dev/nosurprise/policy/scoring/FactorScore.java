package dev.nosurprise.policy.scoring;

import java.math.BigDecimal;

/**
 * Строка расшифровки для админа: какое значение, какой балл, с каким весом и сколько внесло в итог.
 * contribution = score × weight; итоговый score = Σ contribution / Σ weight.
 */
public record FactorScore(Factor factor, String value, BigDecimal score, BigDecimal weight, BigDecimal contribution) {
}
