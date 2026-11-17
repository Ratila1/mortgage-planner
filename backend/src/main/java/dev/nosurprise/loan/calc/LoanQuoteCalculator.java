package dev.nosurprise.loan.calc;

import dev.nosurprise.common.Money;

import java.math.BigDecimal;

/**
 * Калькулятор микрозайма (MICROLOANS.md §3.2) — чистая функция без Spring и IO, как calculator.
 * Используется и в публичном /quote, и при подаче: сумма в заявке пересчитывается на сервере,
 * клиенту не доверяем.
 * <pre>
 * interest = round(amount × daily_rate / 100 × term_days)
 * total    = amount + interest + modifier
 * </pre>
 * Проценты считаются только от суммы займа: модификатор — фиксированная надбавка типа, на неё не начисляется.
 */
public final class LoanQuoteCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private LoanQuoteCalculator() {
    }

    public static LoanQuote quote(BigDecimal amount, int termDays, BigDecimal dailyRate, BigDecimal modifier) {
        BigDecimal interest = Money.round(amount
                .multiply(dailyRate, Money.MC)
                .divide(HUNDRED, Money.MC)
                .multiply(BigDecimal.valueOf(termDays), Money.MC));
        BigDecimal roundedModifier = Money.round(modifier);
        BigDecimal total = Money.round(amount).add(interest).add(roundedModifier);
        return new LoanQuote(Money.round(amount), termDays, dailyRate, interest, roundedModifier, total);
    }
}
