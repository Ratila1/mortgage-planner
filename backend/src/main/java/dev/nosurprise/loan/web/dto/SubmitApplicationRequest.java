package dev.nosurprise.loan.web.dto;

import dev.nosurprise.customer.web.dto.ProfileRequest;
import dev.nosurprise.loan.application.SubmitCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** Подача заявки: параметры займа и форма профиля (зарплата — в profile, §3.3). */
public record SubmitApplicationRequest(
        @NotNull UUID loanTypeId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull Integer termDays,
        @NotNull @Valid ProfileRequest profile
) {

    public SubmitCommand toCommand() {
        return new SubmitCommand(loanTypeId, amount, termDays, profile.toData());
    }
}
