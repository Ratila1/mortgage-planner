package dev.nosurprise.offer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bank_offers")
public class BankOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    @Column(name = "min_down_payment_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal minDownPaymentPercent;

    @Column(name = "max_term_months", nullable = false)
    private Integer maxTermMonths;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version;

    protected BankOffer() {
    }

    public BankOffer(String bankName, BigDecimal rate, BigDecimal minDownPaymentPercent,
                     Integer maxTermMonths, boolean active) {
        this.bankName = bankName;
        this.rate = rate;
        this.minDownPaymentPercent = minDownPaymentPercent;
        this.maxTermMonths = maxTermMonths;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public BigDecimal getMinDownPaymentPercent() {
        return minDownPaymentPercent;
    }

    public void setMinDownPaymentPercent(BigDecimal minDownPaymentPercent) {
        this.minDownPaymentPercent = minDownPaymentPercent;
    }

    public Integer getMaxTermMonths() {
        return maxTermMonths;
    }

    public void setMaxTermMonths(Integer maxTermMonths) {
        this.maxTermMonths = maxTermMonths;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getVersion() {
        return version;
    }
}