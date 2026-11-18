package dev.nosurprise.loan.web.dto;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.calc.DebtLoad;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Строка списка заявок админа (§3.4): всё из снимка, без join на профиль. */
public record AdminApplicationRow(UUID id, Instant createdAt, String lastName, String firstName, String middleName,
                                  String loanTypeName, String amount, int termDays, String monthlyIncome,
                                  String debtLoadPercent, boolean debtLoadWarning, String status) {

    public static AdminApplicationRow from(LoanApplication application, String loanTypeName,
                                           BigDecimal warningPercent) {
        var applicant = application.getApplicant();
        BigDecimal debtLoad = DebtLoad.percent(application.getTerms().getTotal(), applicant.getMonthlyIncome());
        return new AdminApplicationRow(application.getId(), application.getCreatedAt(), applicant.getLastName(),
                applicant.getFirstName(), applicant.getMiddleName(), loanTypeName,
                application.getAmount().toPlainString(), application.getTermDays(),
                applicant.getMonthlyIncome().toPlainString(), debtLoad.toPlainString(),
                DebtLoad.exceeds(debtLoad, warningPercent), application.getStatus().name());
    }
}
