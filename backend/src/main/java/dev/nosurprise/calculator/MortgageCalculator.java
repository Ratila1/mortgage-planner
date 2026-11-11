package dev.nosurprise.calculator;

import dev.nosurprise.common.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Чистое расчётное ядро (§5): без Spring, БД и IO. Зависит только от common.Money.
 * <p>
 * Правила:
 * <ul>
 *   <li>деньги только BigDecimal; каждое деление — с явными scale и RoundingMode.HALF_UP;</li>
 *   <li>проценты месяца округляются до копеек (HALF_UP) и уже округлённые входят в остаток;</li>
 *   <li>последняя строка графика гасит остаток полностью — remaining становится ровно 0.00;</li>
 *   <li>досрочные погашения применяются после планового платежа того же месяца и ограничены остатком;</li>
 *   <li>переплата = сумма начисленных процентов.</li>
 * </ul>
 */
public class MortgageCalculator {

    /** scale промежуточных делений (месячная ставка, платёж и основной долг до округления в копейки). */
    private static final int DIVISION_SCALE = 12;
    /** годовая ставка % → месячная доля: annualRate / 100 / 12 = annualRate / 1200. */
    private static final BigDecimal RATE_DIVISOR = new BigDecimal("1200");
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");

    public CalculationResult calculate(LoanParams params) {
        BigDecimal loanAmount = Money.round(params.loanAmount());
        CalculationResult result = compute(loanAmount, params);
        if (params.earlyRepayments().isEmpty()) {
            return result;
        }
        // «Сэкономили»: сравнение с расчётом без досрочных погашений (§5).
        CalculationResult base = compute(loanAmount, withoutEarlyRepayments(params));
        Savings savings = new Savings(
                Money.round(base.summary().overpayment().subtract(result.summary().overpayment())),
                base.summary().termMonths() - result.summary().termMonths());
        return new CalculationResult(result.summary(), result.schedule(), savings);
    }

    private LoanParams withoutEarlyRepayments(LoanParams params) {
        return new LoanParams(params.price(), params.downPayment(), params.termMonths(),
                params.annualRate(), params.type(), List.of());
    }

    private CalculationResult compute(BigDecimal loanAmount, LoanParams params) {
        BigDecimal monthlyRate = params.annualRate().divide(RATE_DIVISOR, DIVISION_SCALE, RoundingMode.HALF_UP);
        int termMonths = params.termMonths();
        boolean annuity = params.type() == PaymentType.ANNUITY;
        Map<Integer, List<EarlyRepayment>> earlyByMonth = expandEarlyRepayments(params.earlyRepayments(), termMonths);

        BigDecimal balance = loanAmount;
        BigDecimal plannedPayment = annuity ? annuityPayment(balance, monthlyRate, termMonths) : null;
        BigDecimal fixedPrincipal = annuity
                ? null
                : Money.round(balance.divide(BigDecimal.valueOf(termMonths), DIVISION_SCALE, RoundingMode.HALF_UP));

        List<ScheduleRow> schedule = new ArrayList<>();
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;

        for (int month = 1; month <= termMonths && balance.signum() > 0; month++) {
            // Проценты месяца: округляем до копеек и далее работаем только с округлённым значением.
            BigDecimal interest = Money.round(balance.multiply(monthlyRate));
            BigDecimal payment;
            BigDecimal principal;
            if (annuity) {
                payment = plannedPayment;
                principal = payment.subtract(interest);
            } else {
                principal = fixedPrincipal;
                payment = principal.add(interest);
            }

            // Последний платёж (или платёж, покрывающий весь остаток): гасим долг полностью,
            // чтобы remaining стал ровно 0.00 и не стал отрицательным.
            boolean closing = principal.compareTo(balance) >= 0 || month == termMonths;
            if (closing) {
                principal = balance;
                payment = principal.add(interest);
                balance = ZERO_MONEY;
            } else {
                balance = balance.subtract(principal);
            }

            BigDecimal earlyAmount = ZERO_MONEY;
            if (!closing) {
                List<EarlyRepayment> monthEars = earlyByMonth.get(month);
                if (monthEars != null) {
                    for (EarlyRepayment er : monthEars) {
                        // Погашение ограничивается текущим остатком (§5).
                        BigDecimal amount = er.amount().min(balance);
                        balance = balance.subtract(amount);
                        earlyAmount = earlyAmount.add(amount);
                        totalPaid = totalPaid.add(amount);
                        if (er.mode() == EarlyRepaymentMode.REDUCE_PAYMENT && balance.signum() > 0) {
                            int remainingMonths = termMonths - month;
                            if (annuity) {
                                // Платёж пересчитывается на оставшийся срок.
                                plannedPayment = annuityPayment(balance, monthlyRate, remainingMonths);
                            } else {
                                // Основной долг: остаток / оставшиеся месяцы.
                                fixedPrincipal = Money.round(
                                        balance.divide(BigDecimal.valueOf(remainingMonths), DIVISION_SCALE, RoundingMode.HALF_UP));
                            }
                        }
                    }
                    if (balance.signum() <= 0) {
                        balance = ZERO_MONEY;
                    }
                }
            }

            totalPaid = totalPaid.add(payment);
            totalInterest = totalInterest.add(interest);
            schedule.add(new ScheduleRow(month,
                    Money.round(payment),
                    Money.round(principal),
                    Money.round(interest),
                    Money.round(earlyAmount),
                    Money.round(balance)));

            if (balance.signum() <= 0) {
                break;
            }
        }

        if (schedule.isEmpty()) {
            throw new IllegalStateException("schedule must not be empty");
        }
        LoanSummary summary = new LoanSummary(
                schedule.get(0).payment(),
                Money.round(totalInterest),
                Money.round(totalPaid),
                schedule.size());
        return new CalculationResult(summary, schedule, null);
    }

    /** Аннуитетный платёж: S·r / (1 − (1+r)^(−n)) ≡ S·r·(1+r)^n / ((1+r)^n − 1); при r = 0 → S / n. */
    private BigDecimal annuityPayment(BigDecimal balance, BigDecimal monthlyRate, int months) {
        if (monthlyRate.signum() == 0) {
            return Money.round(balance.divide(BigDecimal.valueOf(months), DIVISION_SCALE, RoundingMode.HALF_UP));
        }
        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(months);
        BigDecimal numerator = balance.multiply(monthlyRate).multiply(growth);
        BigDecimal denominator = growth.subtract(BigDecimal.ONE);
        return Money.round(numerator.divide(denominator, DIVISION_SCALE, RoundingMode.HALF_UP));
    }

    /** Раскидывает погашения по месяцам: REGULAR разворачивается в startMonth, startMonth+everyMonths, ... */
    private Map<Integer, List<EarlyRepayment>> expandEarlyRepayments(List<EarlyRepayment> earlyRepayments, int termMonths) {
        Map<Integer, List<EarlyRepayment>> byMonth = new TreeMap<>();
        for (EarlyRepayment er : earlyRepayments) {
            if (er.type() == EarlyRepaymentType.ONE_TIME) {
                int month = er.month();
                if (month > termMonths) {
                    throw new IllegalArgumentException(
                            "earlyRepayment month " + month + " is beyond term " + termMonths);
                }
                byMonth.computeIfAbsent(month, m -> new ArrayList<>()).add(er);
            } else {
                int start = er.startMonth();
                if (start > termMonths) {
                    throw new IllegalArgumentException(
                            "earlyRepayment startMonth " + start + " is beyond term " + termMonths);
                }
                int limit = er.untilMonth() != null ? Math.min(er.untilMonth(), termMonths) : termMonths;
                for (int month = start; month <= limit; month += er.everyMonths()) {
                    byMonth.computeIfAbsent(month, m -> new ArrayList<>()).add(er);
                }
            }
        }
        return byMonth;
    }
}
