package dev.nosurprise.loan.application;

import dev.nosurprise.loan.calc.LoanQuote;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * Снимок условий на момент подачи (MICROLOANS.md §3.6). Копируем, а не ссылаемся на тип:
 * админ может поменять ставку или модификатор, а договор должен остаться на условиях подачи.
 * Сеттеров нет — снимок создаётся один раз.
 */
@Embeddable
public class LoanTerms {

    @Column(name = "daily_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal dailyRate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal modifier;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal interest;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    protected LoanTerms() {
    }

    static LoanTerms from(LoanQuote quote) {
        LoanTerms terms = new LoanTerms();
        terms.dailyRate = quote.dailyRate();
        terms.modifier = quote.modifier();
        terms.interest = quote.interest();
        terms.total = quote.total();
        return terms;
    }

    public BigDecimal getDailyRate() {
        return dailyRate;
    }

    public BigDecimal getModifier() {
        return modifier;
    }

    public BigDecimal getInterest() {
        return interest;
    }

    public BigDecimal getTotal() {
        return total;
    }
}
