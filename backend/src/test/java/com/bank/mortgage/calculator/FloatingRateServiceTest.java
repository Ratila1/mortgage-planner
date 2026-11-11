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

class FloatingRateServiceTest {

    private FloatingRateService floatingRateService;
    private CalculatorService calculatorService;

    @BeforeEach
    void setUp() {
        calculatorService = new CalculatorServiceImpl(new InMemoryCacheService());
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
                new BigDecimal("0.10"),
                PaymentType.ANNUITY,
                List.of()
        );

        // when: rate increases by 5% (from 10% to 10.5%)
        Mono<CalculationResponse> result = floatingRateService.calculateWithRateIncrease(baseRequest, 5);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    CalculationResponse baseResponse = calculatorService.calculate(baseRequest).block();
                    assertThat(response.monthlyPayment()).isGreaterThan(baseResponse.monthlyPayment());
                    assertThat(response.overpayment()).isGreaterThan(baseResponse.overpayment());
                    assertThat(response.totalPayment()).isGreaterThan(baseResponse.totalPayment());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("calculateWithRateIncrease_withLargeIncrease_showsSignificantDifference")
    void calculateWithRateIncrease_withLargeIncrease_showsSignificantDifference() {
        // given
        CalculationRequest baseRequest = new CalculationRequest(
                new BigDecimal("10000000"),
                new BigDecimal("2000000"),
                240,
                new BigDecimal("0.08"),
                PaymentType.ANNUITY,
                List.of()
        );

        // when: rate increases by 3% (from 8% to 11%)
        Mono<CalculationResponse> result = floatingRateService.calculateWithRateIncrease(baseRequest, 3);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    CalculationResponse baseResponse = calculatorService.calculate(baseRequest).block();
                    // Monthly payment should increase significantly
                    assertThat(response.monthlyPayment()).isGreaterThan(baseResponse.monthlyPayment());
                    // Overpayment should increase significantly
                    BigDecimal overpaymentIncrease = response.overpayment().subtract(baseResponse.overpayment());
                    assertThat(overpaymentIncrease).isGreaterThan(new BigDecimal("1000000"));
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

    @Test
    @DisplayName("calculateWithRateIncrease_differentialType_worksCorrectly")
    void calculateWithRateIncrease_differentialType_worksCorrectly() {
        // given
        CalculationRequest baseRequest = new CalculationRequest(
                new BigDecimal("4000000"),
                new BigDecimal("800000"),
                120,
                new BigDecimal("0.10"),
                PaymentType.DIFFERENTIAL,
                List.of()
        );

        // when: rate increases by 2%
        Mono<CalculationResponse> result = floatingRateService.calculateWithRateIncrease(baseRequest, 2);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    CalculationResponse baseResponse = calculatorService.calculate(baseRequest).block();
                    assertThat(response.monthlyPayment()).isGreaterThan(baseResponse.monthlyPayment());
                    // Payments should still decrease
                    for (int i = 1; i < response.schedule().size(); i++) {
                        assertThat(response.schedule().get(i).payment())
                                .isLessThanOrEqualTo(response.schedule().get(i - 1).payment());
                    }
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("calculateWithRateIncrease_preservesEarlyRepayments")
    void calculateWithRateIncrease_preservesEarlyRepayments() {
        // given
        CalculationRequest baseRequest = new CalculationRequest(
                new BigDecimal("3000000"),
                new BigDecimal("500000"),
                60,
                new BigDecimal("0.10"),
                PaymentType.ANNUITY,
                List.of(new EarlyRepayment(new BigDecimal("200000"), 12, EarlyRepaymentMode.REDUCE_TERM))
        );

        // when
        Mono<CalculationResponse> result = floatingRateService.calculateWithRateIncrease(baseRequest, 3);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    // Early repayment should still be applied
                    assertThat(response.schedule().size()).isLessThan(60);
                    assertThat(response.schedule().get(response.schedule().size() - 1).remaining())
                            .isEqualByComparingTo("0.00");
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