package dev.nosurprise.loan.application;

import dev.nosurprise.customer.ProfileView;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Снимок данных заявителя на момент подачи (MICROLOANS.md §3.6): админ видит то, что человек подал,
 * даже если он потом поменял профиль. Заодно список заявок строится без join на профиль.
 */
@Embeddable
public class ApplicantSnapshot {

    @Column(name = "applicant_last_name", nullable = false)
    private String lastName;

    @Column(name = "applicant_first_name", nullable = false)
    private String firstName;

    @Column(name = "applicant_middle_name")
    private String middleName;

    @Column(name = "applicant_phone", nullable = false)
    private String phone;

    @Column(name = "applicant_monthly_income", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyIncome;

    @Column(name = "kyc_document_id", nullable = false)
    private UUID kycDocumentId;

    protected ApplicantSnapshot() {
    }

    static ApplicantSnapshot from(ProfileView profile, UUID kycDocumentId) {
        ApplicantSnapshot snapshot = new ApplicantSnapshot();
        snapshot.lastName = profile.lastName();
        snapshot.firstName = profile.firstName();
        snapshot.middleName = profile.middleName();
        snapshot.phone = profile.phone();
        snapshot.monthlyIncome = profile.monthlyIncome();
        snapshot.kycDocumentId = kycDocumentId;
        return snapshot;
    }

    public String getLastName() {
        return lastName;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public String getPhone() {
        return phone;
    }

    public BigDecimal getMonthlyIncome() {
        return monthlyIncome;
    }

    public UUID getKycDocumentId() {
        return kycDocumentId;
    }
}
