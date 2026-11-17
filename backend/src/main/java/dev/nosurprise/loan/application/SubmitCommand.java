package dev.nosurprise.loan.application;

import dev.nosurprise.customer.ProfileData;

import java.math.BigDecimal;
import java.util.UUID;

/** Подача заявки: параметры займа и данные формы профиля (они сохраняются в той же транзакции, §3.3). */
public record SubmitCommand(UUID loanTypeId, BigDecimal amount, int termDays, ProfileData profile) {
}
