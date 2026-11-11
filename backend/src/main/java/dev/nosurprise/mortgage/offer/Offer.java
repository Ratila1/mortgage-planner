package dev.nosurprise.mortgage.offer;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Table("offers")
public record Offer(
        @Id Long id,
        String bankName,
        BigDecimal rate,
        BigDecimal minDownPaymentPercent,
        int maxTermMonths,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
    public static OfferBuilder builder() {
        return new OfferBuilder();
    }

    public static class OfferBuilder {
        private Long id;
        private String bankName;
        private BigDecimal rate;
        private BigDecimal minDownPaymentPercent;
        private int maxTermMonths;
        private boolean isActive = true;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();

        public OfferBuilder id(Long id) { this.id = id; return this; }
        public OfferBuilder bankName(String bankName) { this.bankName = bankName; return this; }
        public OfferBuilder rate(BigDecimal rate) { this.rate = rate; return this; }
        public OfferBuilder minDownPaymentPercent(BigDecimal minDownPaymentPercent) { this.minDownPaymentPercent = minDownPaymentPercent; return this; }
        public OfferBuilder maxTermMonths(int maxTermMonths) { this.maxTermMonths = maxTermMonths; return this; }
        public OfferBuilder isActive(boolean isActive) { this.isActive = isActive; return this; }
        public OfferBuilder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public OfferBuilder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public Offer build() {
            return new Offer(id, bankName, rate, minDownPaymentPercent, maxTermMonths, isActive, createdAt, updatedAt);
        }
    }
}







