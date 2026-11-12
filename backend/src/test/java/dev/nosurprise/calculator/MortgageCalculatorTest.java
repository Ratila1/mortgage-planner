package dev.nosurprise.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Эталоны §5: кредит 1 000 000, 12 % годовых, 12 месяцев.
 * Аннуитет: платёж 88848.79. Дифференцированный: первый платёж 93333.33.
 */
class MortgageCalculatorTest {

    private final MortgageCalculator calculator = new MortgageCalculator();

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("Аннуитет")
    class AnnuityTests {

        @Test
        @DisplayName("эталон: 1 000 000, 12 %, 12 мес → платёж 88848.79")
        void referenceValues() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("12"), PaymentType.ANNUITY, List.of()));

            assertThat(result.summary().monthlyPayment()).isEqualByComparingTo("88848.79");
            assertThat(result.schedule()).hasSize(12);
            assertThat(result.savings()).isNull();

            ScheduleRow first = result.schedule().get(0);
            assertThat(first.month()).isEqualTo(1);
            assertThat(first.payment()).isEqualByComparingTo("88848.79");
            assertThat(first.principal()).isEqualByComparingTo("78848.79");
            assertThat(first.interest()).isEqualByComparingTo("10000.00");
            assertThat(first.earlyRepayment()).isEqualByComparingTo("0.00");
            assertThat(first.balance()).isEqualByComparingTo("921151.21");

            // Последняя строка закрывает долг ровно до 0.00.
            ScheduleRow last = result.schedule().get(11);
            assertThat(last.balance()).isEqualByComparingTo("0.00");

            // Переплата = сумма начисленных процентов = totalPaid − loanAmount.
            assertThat(result.summary().overpayment()).isEqualByComparingTo("66185.45");
            assertThat(result.summary().totalPaid()).isEqualByComparingTo("1066185.45");
            assertThat(result.summary().overpayment())
                    .isEqualByComparingTo(result.summary().totalPaid().subtract(money("1000000")));
        }

        @Test
        @DisplayName("ставка 0: платёж = S/n, проценты 0.00, хвост закрывается последним платежом")
        void zeroRate() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("0"), PaymentType.ANNUITY, List.of()));

            assertThat(result.summary().monthlyPayment()).isEqualByComparingTo("83333.33");
            assertThat(result.schedule()).hasSize(12);
            assertThat(result.schedule()).allSatisfy(row -> assertThat(row.interest()).isEqualByComparingTo("0.00"));
            assertThat(result.summary().overpayment()).isEqualByComparingTo("0.00");
            // 11 платежей по 83333.33 = 916666.63, последний гасит остаток 83333.37.
            ScheduleRow last = result.schedule().get(11);
            assertThat(last.payment()).isEqualByComparingTo("83333.37");
            assertThat(last.principal()).isEqualByComparingTo("83333.37");
            assertThat(last.balance()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("срок 1 месяц: один платёж долг + проценты")
        void oneMonthTerm() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 1, money("12"), PaymentType.ANNUITY, List.of()));

            assertThat(result.schedule()).hasSize(1);
            ScheduleRow row = result.schedule().get(0);
            assertThat(row.payment()).isEqualByComparingTo("1010000.00");
            assertThat(row.principal()).isEqualByComparingTo("1000000.00");
            assertThat(row.interest()).isEqualByComparingTo("10000.00");
            assertThat(row.balance()).isEqualByComparingTo("0.00");
            assertThat(result.summary().overpayment()).isEqualByComparingTo("10000.00");
        }
    }

    @Nested
    @DisplayName("Дифференцированный")
    class DifferentiatedTests {

        @Test
        @DisplayName("эталон: первый платёж 93333.33 (83333.33 долг + 10000.00 проценты)")
        void referenceValues() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("12"), PaymentType.DIFFERENTIATED, List.of()));

            assertThat(result.summary().monthlyPayment()).isEqualByComparingTo("93333.33");
            ScheduleRow first = result.schedule().get(0);
            assertThat(first.payment()).isEqualByComparingTo("93333.33");
            assertThat(first.principal()).isEqualByComparingTo("83333.33");
            assertThat(first.interest()).isEqualByComparingTo("10000.00");

            // 11 платежей по 83333.33, последний гасит остаток 83333.37 → платёж 84166.70.
            ScheduleRow last = result.schedule().get(11);
            assertThat(last.payment()).isEqualByComparingTo("84166.70");
            assertThat(last.principal()).isEqualByComparingTo("83333.37");
            assertThat(last.interest()).isEqualByComparingTo("833.33");
            assertThat(last.balance()).isEqualByComparingTo("0.00");

            assertThat(result.schedule()).hasSize(12);
            assertThat(result.summary().overpayment()).isEqualByComparingTo("65000.00");
            assertThat(result.summary().totalPaid()).isEqualByComparingTo("1065000.00");
        }
    }

    @Nested
    @DisplayName("Досрочные погашения")
    class EarlyRepaymentTests {

        @Test
        @DisplayName("разовое погашение больше остатка ограничивается остатком")
        void oneTimeCappedByBalance() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("12"), PaymentType.ANNUITY,
                    List.of(EarlyRepayment.oneTime(3, money("5000000"), EarlyRepaymentMode.REDUCE_TERM))));

            // Кредит закрывается в месяце 3: платёж м3, затем остаток 761080.28 гасится досрочно.
            assertThat(result.schedule()).hasSize(3);
            ScheduleRow last = result.schedule().get(2);
            assertThat(last.earlyRepayment()).isEqualByComparingTo("761080.28");
            assertThat(last.balance()).isEqualByComparingTo("0.00");

            assertThat(result.summary().overpayment()).isEqualByComparingTo("27626.65");
            Savings savings = result.savings();
            assertThat(savings.monthsSaved()).isEqualTo(9);
            assertThat(savings.overpaymentSaved()).isEqualByComparingTo("38558.80");
        }

        @Test
        @DisplayName("аннуитет + REDUCE_TERM: платёж остаётся, срок сокращается")
        void reduceTermAnnuity() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("12"), PaymentType.ANNUITY,
                    List.of(EarlyRepayment.oneTime(2, money("200000"), EarlyRepaymentMode.REDUCE_TERM))));

            assertThat(result.schedule()).hasSize(10);
            // Все плановые платежи неизменны, последний — закрывающий.
            for (int i = 0; i < 9; i++) {
                assertThat(result.schedule().get(i).payment()).isEqualByComparingTo("88848.79");
            }
            assertThat(result.schedule().get(9).balance()).isEqualByComparingTo("0.00");
            assertThat(result.savings().monthsSaved()).isEqualTo(2);
            assertThat(result.savings().overpaymentSaved()).isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("аннуитет + REDUCE_PAYMENT: срок остаётся, платёж пересчитывается")
        void reducePaymentAnnuity() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("12"), PaymentType.ANNUITY,
                    List.of(EarlyRepayment.oneTime(3, money("100000"), EarlyRepaymentMode.REDUCE_PAYMENT))));

            assertThat(result.schedule()).hasSize(12);
            for (int i = 0; i < 3; i++) {
                assertThat(result.schedule().get(i).payment()).isEqualByComparingTo("88848.79");
            }
            // С месяца 4 платёж одинаковый и меньше исходного.
            BigDecimal recalculated = result.schedule().get(3).payment();
            assertThat(recalculated).isLessThan(money("88848.79"));
            for (int i = 4; i < 11; i++) {
                assertThat(result.schedule().get(i).payment()).isEqualByComparingTo(recalculated);
            }
            // Последний платёж закрывает остаток ровно до 0.00.
            ScheduleRow last = result.schedule().get(11);
            assertThat(last.balance()).isEqualByComparingTo("0.00");
            // Остаточная копейка от округления платежа вниз входит в закрывающий платёж.
            assertThat(last.payment()).isEqualByComparingTo("77174.76");
            assertThat(last.interest()).isEqualByComparingTo("764.11");
            assertThat(result.savings().monthsSaved()).isEqualTo(0);
            assertThat(result.savings().overpaymentSaved()).isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("дифференцированный + REDUCE_PAYMENT: основной долг = остаток / оставшиеся месяцы")
        void reducePaymentDifferentiated() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("12"), PaymentType.DIFFERENTIATED,
                    List.of(EarlyRepayment.oneTime(3, money("100000"), EarlyRepaymentMode.REDUCE_PAYMENT))));

            // После платежа м3 остаток 750000.01, минус 100000 → 650000.01 / 9 = 72222.22.
            ScheduleRow row4 = result.schedule().get(3);
            assertThat(row4.principal()).isEqualByComparingTo("72222.22");
            assertThat(row4.interest()).isEqualByComparingTo("6500.00");
            assertThat(row4.payment()).isEqualByComparingTo("78722.22");

            assertThat(result.schedule()).hasSize(12);
            for (int i = 4; i < 11; i++) {
                assertThat(result.schedule().get(i).principal()).isEqualByComparingTo("72222.22");
            }
            // Последний платёж закрывает накопленный остаток копеек.
            ScheduleRow last = result.schedule().get(11);
            assertThat(last.principal()).isEqualByComparingTo("72222.25");
            assertThat(last.payment()).isEqualByComparingTo("72944.47");
            assertThat(last.balance()).isEqualByComparingTo("0.00");
            assertThat(result.savings().monthsSaved()).isEqualTo(0);
        }

        @Test
        @DisplayName("дифференцированный + REDUCE_TERM: основной долг не меняется, срок сокращается")
        void reduceTermDifferentiated() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("12"), PaymentType.DIFFERENTIATED,
                    List.of(EarlyRepayment.oneTime(3, money("100000"), EarlyRepaymentMode.REDUCE_TERM))));

            assertThat(result.schedule()).hasSize(11);
            for (int i = 0; i < 10; i++) {
                assertThat(result.schedule().get(i).principal()).isEqualByComparingTo("83333.33");
            }
            ScheduleRow last = result.schedule().get(10);
            assertThat(last.principal()).isEqualByComparingTo("66666.70");
            assertThat(last.balance()).isEqualByComparingTo("0.00");
            assertThat(result.savings().monthsSaved()).isEqualTo(1);
        }

        @Test
        @DisplayName("регулярные погашения до конца срока (ставка 0, REDUCE_PAYMENT)")
        void regularUntilEndOfTerm() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1200000"), money("0"), 24, money("0"), PaymentType.ANNUITY,
                    List.of(EarlyRepayment.regular(6, 6, money("100000"), null, EarlyRepaymentMode.REDUCE_PAYMENT))));

            assertThat(result.schedule()).hasSize(24);
            // Погашения в месяцах 6, 12, 18; в 24 кредит уже закрыт плановым платежом.
            assertThat(result.schedule().get(5).earlyRepayment()).isEqualByComparingTo("100000.00");
            assertThat(result.schedule().get(11).earlyRepayment()).isEqualByComparingTo("100000.00");
            assertThat(result.schedule().get(17).earlyRepayment()).isEqualByComparingTo("100000.00");
            assertThat(result.schedule().get(23).earlyRepayment()).isEqualByComparingTo("0.00");

            // Платёж пересчитывается после каждого погашения.
            assertThat(result.schedule().get(0).payment()).isEqualByComparingTo("50000.00");
            assertThat(result.schedule().get(6).payment()).isEqualByComparingTo("44444.44");
            assertThat(result.schedule().get(12).payment()).isEqualByComparingTo("36111.11");
            assertThat(result.schedule().get(18).payment()).isEqualByComparingTo("19444.45");

            ScheduleRow last = result.schedule().get(23);
            assertThat(last.payment()).isEqualByComparingTo("19444.45");
            assertThat(last.balance()).isEqualByComparingTo("0.00");

            assertThat(result.summary().overpayment()).isEqualByComparingTo("0.00");
            assertThat(result.savings().monthsSaved()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Инварианты графика")
    class InvariantTests {

        @Test
        @DisplayName("нет отрицательных значений; Σprincipal + ΣearlyRepayment = loanAmount")
        void scheduleInvariants() {
            CalculationResult result = calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("12"), PaymentType.ANNUITY,
                    List.of(
                            EarlyRepayment.oneTime(2, money("100000"), EarlyRepaymentMode.REDUCE_TERM),
                            EarlyRepayment.regular(4, 2, money("5000"), null, EarlyRepaymentMode.REDUCE_PAYMENT)
                    )));

            assertThat(result.schedule()).isNotEmpty();
            BigDecimal prevBalance = money("1000000");
            BigDecimal principalSum = BigDecimal.ZERO;
            BigDecimal earlySum = BigDecimal.ZERO;
            BigDecimal interestSum = BigDecimal.ZERO;
            BigDecimal paidSum = BigDecimal.ZERO;
            for (ScheduleRow row : result.schedule()) {
                assertThat(row.balance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertThat(row.payment()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertThat(row.principal()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertThat(row.interest()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertThat(row.balance()).isLessThanOrEqualTo(prevBalance);
                prevBalance = row.balance();
                principalSum = principalSum.add(row.principal());
                earlySum = earlySum.add(row.earlyRepayment());
                interestSum = interestSum.add(row.interest());
                paidSum = paidSum.add(row.payment()).add(row.earlyRepayment());
            }
            assertThat(prevBalance).isEqualByComparingTo("0.00");
            assertThat(principalSum.add(earlySum)).isEqualByComparingTo("1000000.00");
            assertThat(interestSum).isEqualByComparingTo(result.summary().overpayment());
            assertThat(paidSum).isEqualByComparingTo(result.summary().totalPaid());
            assertThat(result.summary().totalPaid().subtract(result.summary().overpayment()))
                    .isEqualByComparingTo("1000000.00");
        }
    }

    @Nested
    @DisplayName("Валидация входа")
    class ValidationTests {

        @Test
        @DisplayName("первоначальный взнос ≥ цены → ошибка")
        void downPaymentNotLessThanPrice() {
            assertThatThrownBy(() -> calculator.calculate(new LoanParams(
                    money("1000000"), money("1000000"), 12, money("12"), PaymentType.ANNUITY, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> calculator.calculate(new LoanParams(
                    money("1000000"), money("1000001"), 12, money("12"), PaymentType.ANNUITY, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("срок вне 1..360 → ошибка")
        void termMonthsBounds() {
            assertThatThrownBy(() -> calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 0, money("12"), PaymentType.ANNUITY, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 361, money("12"), PaymentType.ANNUITY, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("ставка вне 0..100 → ошибка")
        void annualRateBounds() {
            assertThatThrownBy(() -> calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("-0.1"), PaymentType.ANNUITY, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("100.01"), PaymentType.ANNUITY, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("сумма кредита должна быть > 0")
        void loanAmountMustBePositive() {
            assertThatThrownBy(() -> calculator.calculate(new LoanParams(
                    money("100"), money("100"), 12, money("12"), PaymentType.ANNUITY, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("разовое погашение за пределами срока → ошибка")
        void earlyRepaymentBeyondTerm() {
            assertThatThrownBy(() -> calculator.calculate(new LoanParams(
                    money("1000000"), money("0"), 12, money("12"), PaymentType.ANNUITY,
                    List.of(EarlyRepayment.oneTime(13, money("1000"), EarlyRepaymentMode.REDUCE_TERM)))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("некорректное регулярное погашение → ошибка")
        void invalidRegularEarlyRepayment() {
            assertThatThrownBy(() -> EarlyRepayment.regular(1, 0, money("1000"), null, EarlyRepaymentMode.REDUCE_TERM))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EarlyRepayment.regular(5, 1, money("1000"), 4, EarlyRepaymentMode.REDUCE_TERM))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EarlyRepayment.regular(1, 1, money("0"), null, EarlyRepaymentMode.REDUCE_TERM))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
