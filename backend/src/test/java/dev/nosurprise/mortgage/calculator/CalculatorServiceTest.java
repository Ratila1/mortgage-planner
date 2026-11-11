package dev.nosurprise.mortgage.calculator;

import dev.nosurprise.mortgage.ratelimit.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalculatorServiceTest {

    private CalculatorService calculatorService;
    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new InMemoryCacheService();
        calculatorService = new CalculatorServiceImpl(cacheService);
    }

    @Nested
    @DisplayName("Аннуитетный расчёт (К1)")
    class AnnuityTests {

        @Test
        @DisplayName("calculateAnnuity_withValidParams_returnsCorrectMonthlyPaymentAndSchedule")
        void calculateAnnuity_withValidParams_returnsCorrectMonthlyPaymentAndSchedule() {
            // given: 10 млн, 20% взнос, 20 лет, 10% годовых
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("10000000"),
                    new BigDecimal("2000000"),
                    240,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when
            Mono<CalculationResponse> result = calculatorService.calculate(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        // Monthly payment: loan=8M, rate=10%/12, term=240
                        // PMT = 8000000 * (0.10/12) * (1+0.10/12)^240 / ((1+0.10/12)^240 - 1) = 77201.73
                        assertThat(response.monthlyPayment()).isEqualByComparingTo("77201.73");
                        // Overpayment = totalPayment - loanAmount
                        assertThat(response.overpayment()).isEqualByComparingTo("10528415.20");
                        // Total payment = monthlyPayment * 240 (approximately)
                        assertThat(response.totalPayment()).isEqualByComparingTo("18528415.20");
                        assertThat(response.schedule()).hasSize(240);
                        // First payment breakdown
                        ScheduleEntry first = response.schedule().get(0);
                        assertThat(first.month()).isEqualTo(1);
                        assertThat(first.payment()).isEqualByComparingTo("77201.73");
                        assertThat(first.principal()).isEqualByComparingTo("10535.07"); // first principal
                        assertThat(first.interest()).isEqualByComparingTo("66666.66"); // first interest
                        assertThat(first.remaining()).isEqualByComparingTo("7989464.93");
                        // Last payment should bring remaining to 0
                        ScheduleEntry last = response.schedule().get(response.schedule().size() - 1);
                        assertThat(last.remaining()).isEqualByComparingTo("0.00");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("calculateAnnuity_withShortTerm_returnsCorrectSchedule")
        void calculateAnnuity_withShortTerm_returnsCorrectSchedule() {
            // given: 1 млн, 0 взнос, 12 мес, 12% годовых
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("1000000"),
                    BigDecimal.ZERO,
                    12,
                    new BigDecimal("0.12"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when
            Mono<CalculationResponse> result = calculatorService.calculate(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        // Monthly payment: loan=1M, rate=12%/12=1%, term=12
                        // PMT = 1000000 * 0.01 * 1.01^12 / (1.01^12 - 1) = 88848.79
                        assertThat(response.monthlyPayment()).isEqualByComparingTo("88848.79");
                        assertThat(response.overpayment()).isEqualByComparingTo("66185.48");
                        assertThat(response.totalPayment()).isEqualByComparingTo("1066185.48");
                        assertThat(response.schedule()).hasSize(12);
                        ScheduleEntry last = response.schedule().get(11);
                        assertThat(last.remaining()).isEqualByComparingTo("0.00");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Дифференцированный расчёт (К2)")
    class DifferentialTests {

        @Test
        @DisplayName("calculateDifferential_withValidParams_returnsDecreasingSchedule")
        void calculateDifferential_withValidParams_returnsDecreasingSchedule() {
            // given: 10 млн, 20% взнос, 20 лет, 10% годовых
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("10000000"),
                    new BigDecimal("2000000"),
                    240,
                    new BigDecimal("0.10"),
                    PaymentType.DIFFERENTIAL,
                    List.of()
            );

            // when
            Mono<CalculationResponse> result = calculatorService.calculate(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.schedule()).hasSize(240);
                        // First payment should be maximum
                        ScheduleEntry first = response.schedule().get(0);
                        assertThat(first.month()).isEqualTo(1);
                        // Last payment should be minimum (principal + small interest)
                        ScheduleEntry last = response.schedule().get(239);
                        assertThat(last.payment()).isLessThan(first.payment());
                        // Sum of principal + interest should equal total payment for each entry
                        for (ScheduleEntry entry : response.schedule()) {
                            BigDecimal sum = entry.principal().add(entry.interest()).setScale(2, RoundingMode.HALF_UP);
                            assertThat(entry.payment()).isEqualByComparingTo(sum);
                        }
                        // Remaining should reach 0
                        assertThat(last.remaining()).isEqualByComparingTo("0.00");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("calculateDifferential_firstPaymentGreaterThanAnnuityFirstPayment")
        void calculateDifferential_firstPaymentGreaterThanAnnuityFirstPayment() {
            // given
            CalculationRequest diffRequest = new CalculationRequest(
                    new BigDecimal("5000000"),
                    new BigDecimal("1000000"),
                    120,
                    new BigDecimal("0.12"),
                    PaymentType.DIFFERENTIAL,
                    List.of()
            );
            CalculationRequest annRequest = new CalculationRequest(
                    new BigDecimal("5000000"),
                    new BigDecimal("1000000"),
                    120,
                    new BigDecimal("0.12"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when
            Mono<CalculationResponse> diffResult = calculatorService.calculate(diffRequest);
            Mono<CalculationResponse> annResult = calculatorService.calculate(annRequest);

            // then
            StepVerifier.create(diffResult.zipWith(annResult))
                    .assertNext(tuple -> {
                        CalculationResponse diff = tuple.getT1();
                        CalculationResponse ann = tuple.getT2();
                        // First differential payment > first annuity payment (which is constant)
                        assertThat(diff.schedule().get(0).payment()).isGreaterThan(ann.monthlyPayment());
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Досрочные погашения - уменьшить срок (К3)")
    class ReduceTermTests {

        @Test
        @DisplayName("calculate_withReduceTermEarlyRepayment_reducesTermNotPayment")
        void calculate_withReduceTermEarlyRepayment_reducesTermNotPayment() {
            // given: 5 млн, 1 млн взнос, 10 лет, 10%, досрочное погашение 1 млн на 12 месяце
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("5000000"),
                    new BigDecimal("1000000"),
                    120,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of(new EarlyRepayment(new BigDecimal("1000000"), 12, EarlyRepaymentMode.REDUCE_TERM))
            );

            // when
            Mono<CalculationResponse> result = calculatorService.calculate(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        // Schedule should end earlier than 120 months
                        assertThat(response.schedule().size()).isLessThan(120);
                        // Monthly payment should remain the same (except last)
                        // Base payment for 4M/120/10% = 52860.29
                        BigDecimal expectedPayment = new BigDecimal("52860.29");
                        for (int i = 0; i < response.schedule().size() - 1; i++) {
                            assertThat(response.schedule().get(i).payment())
                                    .isEqualByComparingTo(expectedPayment);
                        }
                        // Last remaining should be 0
                        assertThat(response.schedule().get(response.schedule().size() - 1).remaining())
                                .isEqualByComparingTo("0.00");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("calculate_withMultipleReduceTermRepayments_reducesTermCorrectly")
        void calculate_withMultipleReduceTermRepayments_reducesTermCorrectly() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("3000000"),
                    BigDecimal.ZERO,
                    60,
                    new BigDecimal("0.15"),
                    PaymentType.ANNUITY,
                    List.of(
                            new EarlyRepayment(new BigDecimal("500000"), 6, EarlyRepaymentMode.REDUCE_TERM),
                            new EarlyRepayment(new BigDecimal("500000"), 18, EarlyRepaymentMode.REDUCE_TERM)
                    )
            );

            // when
            Mono<CalculationResponse> result = calculatorService.calculate(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.schedule().size()).isLessThan(60);
                        assertThat(response.schedule().get(response.schedule().size() - 1).remaining())
                                .isEqualByComparingTo("0.00");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Досрочные погашения - уменьшить платёж (К4)")
    class ReducePaymentTests {

        @Test
        @DisplayName("calculate_withReducePaymentEarlyRepayment_reducesPaymentNotTerm")
        void calculate_withReducePaymentEarlyRepayment_reducesPaymentNotTerm() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("4000000"),
                    new BigDecimal("1000000"),
                    120,
                    new BigDecimal("0.12"),
                    PaymentType.ANNUITY,
                    List.of(new EarlyRepayment(new BigDecimal("1000000"), 12, EarlyRepaymentMode.REDUCE_PAYMENT))
            );

            // when
            Mono<CalculationResponse> result = calculatorService.calculate(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        // Term should remain 120 months (or close to it)
                        assertThat(response.schedule().size()).isEqualTo(120);
                        // Payment after month 12 should be reduced
                        BigDecimal paymentBefore = response.schedule().get(10).payment(); // month 11
                        BigDecimal paymentAfter = response.schedule().get(11).payment();  // month 12
                        assertThat(paymentAfter).isLessThan(paymentBefore);
                        // Last remaining should be 0
                        assertThat(response.schedule().get(119).remaining())
                                .isEqualByComparingTo("0.00");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Регулярные досрочные погашения (К5)")
    class RegularEarlyRepaymentTests {

        @Test
        @DisplayName("calculate_withRegularMonthlyEarlyRepayment_appliesCorrectly")
        void calculate_withRegularMonthlyEarlyRepayment_appliesCorrectly() {
            // given: regular 10,000 monthly extra payment starting from month 1
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("2000000"),
                    BigDecimal.ZERO,
                    60,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of(
                            new EarlyRepayment(new BigDecimal("10000"), 1, EarlyRepaymentMode.REDUCE_TERM),
                            new EarlyRepayment(new BigDecimal("10000"), 2, EarlyRepaymentMode.REDUCE_TERM),
                            new EarlyRepayment(new BigDecimal("10000"), 3, EarlyRepaymentMode.REDUCE_TERM)
                    )
            );

            // when
            Mono<CalculationResponse> result = calculatorService.calculate(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        // Schedule should be shorter due to regular extra payments
                        assertThat(response.schedule().size()).isLessThan(60);
                        assertThat(response.schedule().get(response.schedule().size() - 1).remaining())
                                .isEqualByComparingTo("0.00");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Плавающая ставка (К6)")
    class FloatingRateTests {

        private FloatingRateService floatingRateService;

        @BeforeEach
        void setUpFloating() {
            floatingRateService = new FloatingRateServiceImpl(calculatorService);
        }

        @Test
        @DisplayName("calculateWithRateIncrease_withValidIncrease_returnsScheduleWithHigherRate")
        void calculateWithRateIncrease_withValidIncrease_returnsScheduleWithHigherRate() {
            // given
            CalculationRequest baseRequest = new CalculationRequest(
                    new BigDecimal("5000000"),
                    new BigDecimal("1000000"),
                    120,
                    new BigDecimal("0.10"), // 10%
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when: rate increases by 5% (new rate = 10.5%)
            Mono<CalculationResponse> result = floatingRateService.calculateWithRateIncrease(baseRequest, 5);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.monthlyPayment()).isGreaterThan(
                                calculatorService.calculate(baseRequest).block().monthlyPayment()
                        );
                        assertThat(response.overpayment()).isGreaterThan(
                                calculatorService.calculate(baseRequest).block().overpayment()
                        );
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("calculateWithRateIncrease_withZeroIncrease_returnsSameAsBase")
        void calculateWithRateIncrease_withZeroIncrease_returnsSameAsBase() {
            // given
            CalculationRequest baseRequest = new CalculationRequest(
                    new BigDecimal("3000000"),
                    new BigDecimal("500000"),
                    60,
                    new BigDecimal("0.12"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when
            Mono<CalculationResponse> baseResult = calculatorService.calculate(baseRequest);
            Mono<CalculationResponse> increasedResult = floatingRateService.calculateWithRateIncrease(baseRequest, 0);

            // then
            StepVerifier.create(baseResult.zipWith(increasedResult))
                    .assertNext(tuple -> {
                        CalculationResponse base = tuple.getT1();
                        CalculationResponse increased = tuple.getT2();
                        assertThat(increased.monthlyPayment()).isEqualByComparingTo(base.monthlyPayment());
                        assertThat(increased.overpayment()).isEqualByComparingTo(base.overpayment());
                        assertThat(increased.totalPayment()).isEqualByComparingTo(base.totalPayment());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("calculateWithRateIncrease_withNegativeIncrease_throwsException")
        void calculateWithRateIncrease_withNegativeIncrease_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("3000000"),
                    new BigDecimal("500000"),
                    60,
                    new BigDecimal("0.12"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when/then
            StepVerifier.create(floatingRateService.calculateWithRateIncrease(request, -1))
                    .expectErrorMatches(throwable ->
                            throwable instanceof IllegalArgumentException &&
                                    throwable.getMessage().contains("non-negative"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("BigDecimal rounding ROUND_HALF_UP (К7)")
    class RoundingTests {

        @Test
        @DisplayName("calculate_usesBigDecimalWithHalfUpRounding_noCentsLost")
        void calculate_usesBigDecimalWithHalfUpRounding_noCentsLost() {
            // given: amount that produces rounding edge cases
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("1000000.55"),
                    new BigDecimal("100000.55"),
                    12,
                    new BigDecimal("0.123456"), // rate with many decimals
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when
            Mono<CalculationResponse> result = calculatorService.calculate(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        // All monetary values should have exactly 2 decimal places
                        assertThat(response.monthlyPayment().scale()).isEqualTo(2);
                        assertThat(response.overpayment().scale()).isEqualTo(2);
                        assertThat(response.totalPayment().scale()).isEqualTo(2);

                        // Sum of principal + interest in schedule should equal totalPayment (within 1 cent)
                        BigDecimal sumSchedule = response.schedule().stream()
                                .map(e -> e.principal().add(e.interest()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .setScale(2, RoundingMode.HALF_UP);
                        assertThat(sumSchedule).isEqualByComparingTo(response.totalPayment());

                        // Remaining at end should be 0
                        assertThat(response.schedule().get(response.schedule().size() - 1).remaining())
                                .isEqualByComparingTo("0.00");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("calculate_withRateProducingRepeatingDecimal_roundsCorrectly")
        void calculate_withRateProducingRepeatingDecimal_roundsCorrectly() {
            // given: 1/3 monthly rate = 0.33333...%
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("3000000"),
                    new BigDecimal("500000"),
                    12,
                    new BigDecimal("0.04"), // 4% annual = 0.333...% monthly
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when
            Mono<CalculationResponse> result = calculatorService.calculate(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        // Verify no rounding errors accumulate
                        assertThat(response.schedule().get(response.schedule().size() - 1).remaining())
                                .isEqualByComparingTo("0.00");
                        // Each payment should be properly rounded to 2 decimals
                        for (ScheduleEntry entry : response.schedule()) {
                            assertThat(entry.payment().scale()).isLessThanOrEqualTo(2);
                            assertThat(entry.principal().scale()).isLessThanOrEqualTo(2);
                            assertThat(entry.interest().scale()).isLessThanOrEqualTo(2);
                            assertThat(entry.remaining().scale()).isLessThanOrEqualTo(2);
                        }
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Boundary cases and validation")
    class BoundaryTests {

        @Test
        @DisplayName("calculate_withZeroRate_throwsException")
        void calculate_withZeroRate_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("3000000"),
                    new BigDecimal("500000"),
                    60,
                    BigDecimal.ZERO,
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when/then
            assertThatThrownBy(() -> calculatorService.calculate(request).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Rate must be positive");
        }

        @Test
        @DisplayName("calculate_withNegativeRate_throwsException")
        void calculate_withNegativeRate_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("3000000"),
                    new BigDecimal("500000"),
                    60,
                    new BigDecimal("-0.05"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when/then
            assertThatThrownBy(() -> calculatorService.calculate(request).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Rate must be positive");
        }

        @Test
        @DisplayName("calculate_withTermZero_throwsException")
        void calculate_withTermZero_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("3000000"),
                    new BigDecimal("500000"),
                    0,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when/then
            assertThatThrownBy(() -> calculatorService.calculate(request).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Term must be positive");
        }

        @Test
        @DisplayName("calculate_withNegativeTerm_throwsException")
        void calculate_withNegativeTerm_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("3000000"),
                    new BigDecimal("500000"),
                    -12,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when/then
            assertThatThrownBy(() -> calculatorService.calculate(request).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Term must be positive");
        }

        @Test
        @DisplayName("calculate_withDownPaymentEqualPropertyPrice_throwsException")
        void calculate_withDownPaymentEqualPropertyPrice_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("3000000"),
                    new BigDecimal("3000000"),
                    60,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when/then
            assertThatThrownBy(() -> calculatorService.calculate(request).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Down payment must be less than property price");
        }

        @Test
        @DisplayName("calculate_withDownPaymentGreaterThanPropertyPrice_throwsException")
        void calculate_withDownPaymentGreaterThanPropertyPrice_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("3000000"),
                    new BigDecimal("4000000"),
                    60,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when/then
            assertThatThrownBy(() -> calculatorService.calculate(request).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Down payment must be less than property price");
        }

        @Test
        @DisplayName("calculate_withEarlyRepaymentExceedsRemaining_throwsException")
        void calculate_withEarlyRepaymentExceedsRemaining_throwsException() {
            // given: try to repay more than loan amount
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("2000000"),
                    new BigDecimal("500000"),
                    60,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of(new EarlyRepayment(new BigDecimal("2000000"), 1, EarlyRepaymentMode.REDUCE_TERM))
            );

            // when/then
            StepVerifier.create(calculatorService.calculate(request))
                    .expectErrorMatches(throwable ->
                            throwable instanceof IllegalArgumentException &&
                                    throwable.getMessage().contains("exceeds remaining balance"))
                    .verify();
        }

        @Test
        @DisplayName("calculate_withEarlyRepaymentMonthExceedsTerm_throwsException")
        void calculate_withEarlyRepaymentMonthExceedsTerm_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("2000000"),
                    new BigDecimal("500000"),
                    12,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of(new EarlyRepayment(new BigDecimal("100000"), 13, EarlyRepaymentMode.REDUCE_TERM))
            );

            // when/then
            assertThatThrownBy(() -> calculatorService.calculate(request).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Early repayment month cannot exceed term");
        }

        @Test
        @DisplayName("calculate_withNullPaymentType_throwsException")
        void calculate_withNullPaymentType_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("2000000"),
                    new BigDecimal("500000"),
                    12,
                    new BigDecimal("0.10"),
                    null,
                    List.of()
            );

            // when/then
            assertThatThrownBy(() -> calculatorService.calculate(request).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Payment type must not be null");
        }

        @Test
        @DisplayName("calculate_withNullPropertyPrice_throwsException")
        void calculate_withNullPropertyPrice_throwsException() {
            // given
            CalculationRequest request = new CalculationRequest(
                    null,
                    new BigDecimal("500000"),
                    12,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when/then
            assertThatThrownBy(() -> calculatorService.calculate(request).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Property price must be positive");
        }
    }

    @Nested
    @DisplayName("Cache behavior (К13)")
    class CacheTests {

        @Test
        @DisplayName("calculate_withSameParamsTwice_returnsCachedResult")
        void calculate_withSameParamsTwice_returnsCachedResult() {
            // given
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("5000000"),
                    new BigDecimal("1000000"),
                    120,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of()
            );

            // when: first call
            Mono<CalculationResponse> first = calculatorService.calculate(request);
            // when: second call (should hit cache)
            Mono<CalculationResponse> second = calculatorService.calculate(request);

            // then
            StepVerifier.create(first.zipWith(second))
                    .assertNext(tuple -> {
                        CalculationResponse r1 = tuple.getT1();
                        CalculationResponse r2 = tuple.getT2();
                        assertThat(r1).isEqualTo(r2);
                    })
                    .verifyComplete();
        }
    }

    // Simple in-memory cache implementation for testing
    private static class InMemoryCacheService implements CacheService {
        private final java.util.Map<String, CalculationResponse> cache = new java.util.concurrent.ConcurrentHashMap<>();

        public Mono<CalculationResponse> get(String key) {
            return Mono.justOrEmpty(cache.get(key));
        }

        public Mono<Void> put(String key, CalculationResponse value, long ttlSeconds) {
            cache.put(key, value);
            return Mono.empty();
        }
    }
}







