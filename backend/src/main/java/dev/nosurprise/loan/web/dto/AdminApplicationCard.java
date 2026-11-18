package dev.nosurprise.loan.web.dto;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.calc.DebtLoad;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Карточка заявки для админа (§3.4, §4.7): одним ответом — заявка из Postgres и кредитная история клиента из Mongo.
 * Фото паспорта сюда не входит: оно грузится отдельным запросом по кнопке.
 */
public record AdminApplicationCard(UUID id, UUID userId, String status, Long version, Instant createdAt,
                                   UUID loanTypeId, String loanTypeName, String amount, int termDays,
                                   String dailyRate, String interest, String modifier, String total,
                                   String lastName, String firstName, String middleName, String phone,
                                   String monthlyIncome, String debtLoadPercent, boolean debtLoadWarning,
                                   UUID decidedBy, Instant decidedAt, String decisionComment, Instant offerExpiresAt,
                                   Instant closedAt, String passportUrl, List<DocumentResponse> documents,
                                   CreditHistoryResponse creditHistory) {

    public static AdminApplicationCard from(LoanApplication a, String loanTypeName, BigDecimal warningPercent,
                                            List<DocumentResponse> documents, CreditHistoryResponse creditHistory) {
        var terms = a.getTerms();
        var applicant = a.getApplicant();
        BigDecimal debtLoad = DebtLoad.percent(terms.getTotal(), applicant.getMonthlyIncome());
        return new AdminApplicationCard(a.getId(), a.getUserId(), a.getStatus().name(), a.getVersion(),
                a.getCreatedAt(), a.getLoanTypeId(), loanTypeName, a.getAmount().toPlainString(), a.getTermDays(),
                terms.getDailyRate().toPlainString(), terms.getInterest().toPlainString(),
                terms.getModifier().toPlainString(), terms.getTotal().toPlainString(),
                applicant.getLastName(), applicant.getFirstName(), applicant.getMiddleName(), applicant.getPhone(),
                applicant.getMonthlyIncome().toPlainString(), debtLoad.toPlainString(),
                DebtLoad.exceeds(debtLoad, warningPercent), a.getDecidedBy(), a.getDecidedAt(),
                a.getDecisionComment(), a.getOfferExpiresAt(), a.getClosedAt(),
                "/api/admin/loan-applications/" + a.getId() + "/passport", documents, creditHistory);
    }
}
