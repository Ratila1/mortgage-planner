package dev.nosurprise.loan.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Данные договора — строго из снимка заявки (§3.10), чтобы договор совпадал с условиями подачи. */
public record ContractData(UUID applicationId, String loanTypeName, String lastName, String firstName,
                           String middleName, String phone, BigDecimal amount, int termDays, BigDecimal dailyRate,
                           BigDecimal interest, BigDecimal modifier, BigDecimal total, Instant decidedAt,
                           Instant offerExpiresAt) {
}
