package com.bank.mortgage.user;

import com.bank.mortgage.calculator.EarlyRepayment;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Document(collection = "scenarios")
public record Scenario(
        @Id String id,
        Long userId,
        String name,
        BigDecimal propertyPrice,
        BigDecimal downPayment,
        int termMonths,
        BigDecimal rate,
        String paymentType, // ANNUITY or DIFFERENTIAL
        List<EarlyRepayment> earlyRepayments,
        BigDecimal monthlyPayment,
        BigDecimal overpayment,
        BigDecimal totalPayment,
        String scheduleHash,
        Instant createdAt,
        Instant updatedAt
) {
    public static ScenarioBuilder builder() {
        return new ScenarioBuilder();
    }

    public static class ScenarioBuilder {
        private String id;
        private Long userId;
        private String name;
        private BigDecimal propertyPrice;
        private BigDecimal downPayment;
        private int termMonths;
        private BigDecimal rate;
        private String paymentType;
        private List<EarlyRepayment> earlyRepayments;
        private BigDecimal monthlyPayment;
        private BigDecimal overpayment;
        private BigDecimal totalPayment;
        private String scheduleHash;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();

        public ScenarioBuilder id(String id) { this.id = id; return this; }
        public ScenarioBuilder userId(Long userId) { this.userId = userId; return this; }
        public ScenarioBuilder name(String name) { this.name = name; return this; }
        public ScenarioBuilder propertyPrice(BigDecimal propertyPrice) { this.propertyPrice = propertyPrice; return this; }
        public ScenarioBuilder downPayment(BigDecimal downPayment) { this.downPayment = downPayment; return this; }
        public ScenarioBuilder termMonths(int termMonths) { this.termMonths = termMonths; return this; }
        public ScenarioBuilder rate(BigDecimal rate) { this.rate = rate; return this; }
        public ScenarioBuilder paymentType(String paymentType) { this.paymentType = paymentType; return this; }
        public ScenarioBuilder earlyRepayments(List<EarlyRepayment> earlyRepayments) { this.earlyRepayments = earlyRepayments; return this; }
        public ScenarioBuilder monthlyPayment(BigDecimal monthlyPayment) { this.monthlyPayment = monthlyPayment; return this; }
        public ScenarioBuilder overpayment(BigDecimal overpayment) { this.overpayment = overpayment; return this; }
        public ScenarioBuilder totalPayment(BigDecimal totalPayment) { this.totalPayment = totalPayment; return this; }
        public ScenarioBuilder scheduleHash(String scheduleHash) { this.scheduleHash = scheduleHash; return this; }
        public ScenarioBuilder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public ScenarioBuilder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public Scenario build() {
            return new Scenario(id, userId, name, propertyPrice, downPayment, termMonths, rate,
                    paymentType, earlyRepayments, monthlyPayment, overpayment, totalPayment,
                    scheduleHash, createdAt, updatedAt);
        }
    }
}