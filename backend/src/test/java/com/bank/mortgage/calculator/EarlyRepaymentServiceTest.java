package com.bank.mortgage.calculator;

import com.bank.mortgage.ratelimit.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EarlyRepaymentServiceTest {

    private EarlyRepaymentService earlyRepaymentService;
    private CalculatorService calculatorService;

    @BeforeEach
    void setUp() {
        calculatorService = new CalculatorServiceImpl(new InMemoryCacheService());
        earlyRepaymentService = new EarlyRepaymentServiceImpl(calculatorService);
    }

    @Test
    @DisplayName("recalculateWithEarlyRepayments_reduceTerm_reducesTermNotPayment")
    void recalculateWithEarlyRepayments_reduceTerm_reducesTermNotPayment() {
        // given
        CalculationRequest originalRequest = new CalculationRequest(
                new BigDecimal("4000000"),
                new BigDecimal("1000000"),
                120,
                new BigDecimal("0.10"),
                PaymentType.ANNUITY,
                List.of()
        );
        List<EarlyRepayment> earlyRepayments = List.of(
                new EarlyRepayment(new BigDecimal("1000000"), 12, EarlyRepaymentMode.REDUCE_TERM)
        );

        // when
        Mono<CalculationResponse> result = earlyRepaymentService.recalculateWithEarlyRepayments(originalRequest, earlyRepayments);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    // Original term was 120, should be reduced
                    assertThat(response.schedule().size()).isLessThan(120);
                    // Monthly payment should remain the same (constant for annuity)
                    BigDecimal originalPayment = calculatorService.calculate(originalRequest).block().monthlyPayment();
                    for (int i = 0; i < response.schedule().size() - 1; i++) {
                        assertThat(response.schedule().get(i).payment())
                                .isEqualByComparingTo(originalPayment);
                    }
                    // Last remaining should be 0
                    assertThat(response.schedule().get(response.schedule().size() - 1).remaining())
                            .isEqualByComparingTo("0.00");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("recalculateWithEarlyRepayments_reducePayment_reducesPaymentNotTerm")
    void recalculateWithEarlyRepayments_reducePayment_reducesPaymentNotTerm() {
        // given
        CalculationRequest originalRequest = new CalculationRequest(
                new BigDecimal("3000000"),
                BigDecimal.ZERO,
                60,
                new BigDecimal("0.12"),
                PaymentType.ANNUITY,
                List.of()
        );
        List<EarlyRepayment> earlyRepayments = List.of(
                new EarlyRepayment(new BigDecimal("500000"), 6, EarlyRepaymentMode.REDUCE_PAYMENT)
        );

        // when
        Mono<CalculationResponse> result = earlyRepaymentService.recalculateWithEarlyRepayments(originalRequest, earlyRepayments);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    // Term should remain 60 months
                    assertThat(response.schedule().size()).isEqualTo(60);
                    // Payment after month 6 should be reduced
                    BigDecimal paymentBefore = response.schedule().get(4).payment(); // month 5
                    BigDecimal paymentAfter = response.schedule().get(5).payment();  // month 6
                    assertThat(paymentAfter).isLessThan(paymentBefore);
                    // Last remaining should be 0
                    assertThat(response.schedule().get(59).remaining())
                            .isEqualByComparingTo("0.00");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("recalculateWithEarlyRepayments_regularMonthlyDeduction_appliesCorrectly")
    void recalculateWithEarlyRepayments_regularMonthlyDeduction_appliesCorrectly() {
        // given
        CalculationRequest originalRequest = new CalculationRequest(
                new BigDecimal("2000000"),
                BigDecimal.ZERO,
                36,
                new BigDecimal("0.10"),
                PaymentType.ANNUITY,
                List.of()
        );
        // Regular 50000 monthly extra payment for first 6 months
        List<EarlyRepayment> earlyRepayments = List.of(
                new EarlyRepayment(new BigDecimal("50000"), 1, EarlyRepaymentMode.REDUCE_TERM),
                new EarlyRepayment(new BigDecimal("50000"), 2, EarlyRepaymentMode.REDUCE_TERM),
                new EarlyRepayment(new BigDecimal("50000"), 3, EarlyRepaymentMode.REDUCE_TERM),
                new EarlyRepayment(new BigDecimal("50000"), 4, EarlyRepaymentMode.REDUCE_TERM),
                new EarlyRepayment(new BigDecimal("50000"), 5, EarlyRepaymentMode.REDUCE_TERM),
                new EarlyRepayment(new BigDecimal("50000"), 6, EarlyRepaymentMode.REDUCE_TERM)
        );

        // when
        Mono<CalculationResponse> result = earlyRepaymentService.recalculateWithEarlyRepayments(originalRequest, earlyRepayments);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    // Schedule should be significantly shorter
                    assertThat(response.schedule().size()).isLessThan(36);
                    assertThat(response.schedule().get(response.schedule().size() - 1).remaining())
                            .isEqualByComparingTo("0.00");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("recalculateWithEarlyRepayments_amountExceedsRemaining_throwsException")
    void recalculateWithEarlyRepayments_amountExceedsRemaining_throwsException() {
        // given
        CalculationRequest originalRequest = new CalculationRequest(
                new BigDecimal("1000000"),
                new BigDecimal("200000"),
                24,
                new BigDecimal("0.15"),
                PaymentType.ANNUITY,
                List.of()
        );
        List<EarlyRepayment> earlyRepayments = List.of(
                new EarlyRepayment(new BigDecimal("1000000"), 1, EarlyRepaymentMode.REDUCE_TERM)
        );

        // when/then
        StepVerifier.create(earlyRepaymentService.recalculateWithEarlyRepayments(originalRequest, earlyRepayments))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                                throwable.getMessage().contains("exceeds remaining balance"))
                .verify();
    }

    @Test
    @DisplayName("recalculateWithEarlyRepayments_differentialWithReduceTerm_worksCorrectly")
    void recalculateWithEarlyRepayments_differentialWithReduceTerm_worksCorrectly() {
        // given
        CalculationRequest originalRequest = new CalculationRequest(
                new BigDecimal("5000000"),
                new BigDecimal("1000000"),
                120,
                new BigDecimal("0.10"),
                PaymentType.DIFFERENTIAL,
                List.of()
        );
        List<EarlyRepayment> earlyRepayments = List.of(
                new EarlyRepayment(new BigDecimal("1000000"), 12, EarlyRepaymentMode.REDUCE_TERM)
        );

        // when
        Mono<CalculationResponse> result = earlyRepaymentService.recalculateWithEarlyRepayments(originalRequest, earlyRepayments);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.schedule().size()).isLessThan(120);
                    assertThat(response.schedule().get(response.schedule().size() - 1).remaining())
                            .isEqualByComparingTo("0.00");
                    // Payments should still decrease (differential pattern)
                    for (int i = 1; i < response.schedule().size(); i++) {
                        assertThat(response.schedule().get(i).payment())
                                .isLessThanOrEqualTo(response.schedule().get(i - 1).payment());
                    }
                })
                .verifyComplete();
    }

    // Simple in-memory cache for testing
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