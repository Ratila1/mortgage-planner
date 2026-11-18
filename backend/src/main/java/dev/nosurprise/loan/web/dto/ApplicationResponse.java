package dev.nosurprise.loan.web.dto;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanStatus;
import dev.nosurprise.loan.calc.DebtLoad;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Заявка для клиента: условия из снимка, статус, причина отказа, документы (§3.13). Деньги строками.
 * ПДН считается на лету из снимка (§3.7).
 */
public record ApplicationResponse(UUID id, UUID loanTypeId, String loanTypeName, String amount, int termDays,
                                  String dailyRate, String interest, String modifier, String total,
                                  String monthlyIncome, String debtLoadPercent, String status,
                                  String rejectionReason, Instant offerExpiresAt, Instant createdAt,
                                  Long version, List<DocumentResponse> documents) {

    public static ApplicationResponse from(LoanApplication application, String loanTypeName,
                                           List<DocumentResponse> documents) {
        var terms = application.getTerms();
        var applicant = application.getApplicant();
        return new ApplicationResponse(application.getId(), application.getLoanTypeId(), loanTypeName,
                application.getAmount().toPlainString(), application.getTermDays(),
                terms.getDailyRate().toPlainString(), terms.getInterest().toPlainString(),
                terms.getModifier().toPlainString(), terms.getTotal().toPlainString(),
                applicant.getMonthlyIncome().toPlainString(),
                DebtLoad.percent(terms.getTotal(), applicant.getMonthlyIncome()).toPlainString(),
                application.getStatus().name(),
                // Причину видит клиент только при отказе; комментарий к одобрению — внутренний.
                application.getStatus() == LoanStatus.REJECTED ? application.getDecisionComment() : null,
                application.getOfferExpiresAt(), application.getCreatedAt(), application.getVersion(), documents);
    }
}
