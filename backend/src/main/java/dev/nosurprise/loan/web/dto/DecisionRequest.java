package dev.nosurprise.loan.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Одобрение или отметка «Погашен»: комментарий необязателен, version обязателен (§7.7). */
public record DecisionRequest(@Size(max = 1000) String comment, @NotNull Long version) {
}
