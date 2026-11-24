package dev.nosurprise.loan.type.web.dto;

import dev.nosurprise.loan.type.LoanType;

import java.util.UUID;

/** Тип займа. Деньги и ставки строками (INSTRUCTION.md §9). */
public record LoanTypeResponse(UUID id, String name, String description, String minAmount, String maxAmount,
                               int minTermDays, int maxTermDays, String dailyRate, String modifier,
                               boolean active, String purposeScore, Long version) {

    public static LoanTypeResponse from(LoanType type) {
        return new LoanTypeResponse(type.getId(), type.getName(), type.getDescription(),
                type.getMinAmount().toPlainString(), type.getMaxAmount().toPlainString(),
                type.getMinTermDays(), type.getMaxTermDays(), type.getDailyRate().toPlainString(),
                type.getModifier().toPlainString(), type.isActive(),
                type.getPurposeScore().toPlainString(), type.getVersion());
    }
}
