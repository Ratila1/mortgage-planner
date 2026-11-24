package dev.nosurprise.loan.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Отметка «Погашен» (CREDIT_POLICY.md §5.1): paidAt — фактическая дата оплаты, по умолчанию сегодня;
 * не в будущем. version обязателен (§7.7). comment принимается для совместимости и не хранится.
 */
public record RepaidRequest(@NotNull Long version, LocalDate paidAt, @Size(max = 1000) String comment) {
}
