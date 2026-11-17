package dev.nosurprise.loan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Отказ: причина обязательна, её увидит клиент (§3.4). */
public record RejectRequest(@NotBlank @Size(max = 1000) String reason, @NotNull Long version) {
}
