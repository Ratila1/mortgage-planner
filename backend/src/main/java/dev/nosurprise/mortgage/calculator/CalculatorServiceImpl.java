package dev.nosurprise.mortgage.calculator;

import dev.nosurprise.mortgage.ratelimit.CacheService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class CalculatorServiceImpl implements CalculatorService {

    private static final MathContext MATH_CONTEXT =
            new MathContext(28, RoundingMode.HALF_UP);

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final long CACHE_TTL_SECONDS = 300;

    private final CacheService cacheService;

    public CalculatorServiceImpl(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public Mono<CalculationResponse> calculate(CalculationRequest request) {
        request.validate();

        if (request.earlyRepayments() != null) {
            for (EarlyRepayment earlyRepayment : request.earlyRepayments()) {
                earlyRepayment.validate();
            }
        }

        String cacheKey = computeCacheKey(request);

        return cacheService.get(cacheKey)
                .switchIfEmpty(
                        Mono.fromCallable(() -> doCalculate(request))
                                .flatMap(response ->
                                        cacheService.put(
                                                        cacheKey,
                                                        response,
                                                        CACHE_TTL_SECONDS
                                                )
                                                .thenReturn(response)
                                )
                );
    }

    private String computeCacheKey(CalculationRequest request) {
        try {
            // Version prefix prevents reuse of results from the old algorithm.
            String data = "v2|" +
                    request.propertyPrice() + "|" +
                    request.downPayment() + "|" +
                    request.termMonths() + "|" +
                    request.rate() + "|" +
                    request.type().name() + "|" +
                    (request.earlyRepayments() != null
                            ? request.earlyRepayments().toString()
                            : "");

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    data.getBytes(StandardCharsets.UTF_8)
            );

            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();

        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }

        return result.toString();
    }

    private CalculationResponse doCalculate(CalculationRequest request) {
        BigDecimal loanAmount = request.loanAmount();

        BigDecimal monthlyRate = request.rate()
                .divide(BigDecimal.valueOf(12), MATH_CONTEXT);

        int termMonths = request.termMonths();

        List<EarlyRepayment> earlyRepayments =
                request.earlyRepayments() != null
                        ? new ArrayList<>(request.earlyRepayments())
                        : new ArrayList<>();

        earlyRepayments.sort(
                Comparator.comparingInt(EarlyRepayment::month)
        );

        if (request.type() == PaymentType.DIFFERENTIAL) {
            return calculateDifferential(
                    loanAmount,
                    monthlyRate,
                    termMonths,
                    earlyRepayments
            );
        }

        BigDecimal monthlyPayment = calculateMonthlyPayment(
                loanAmount,
                monthlyRate,
                termMonths,
                PaymentType.ANNUITY
        );

        return calculateAnnuity(
                loanAmount,
                monthlyRate,
                termMonths,
                monthlyPayment,
                earlyRepayments
        );
    }

    private CalculationResponse calculateAnnuity(
            BigDecimal loanAmount,
            BigDecimal monthlyRate,
            int termMonths,
            BigDecimal monthlyPayment,
            List<EarlyRepayment> earlyRepayments
    ) {
        List<ScheduleEntry> schedule = new ArrayList<>();

        BigDecimal remaining = loanAmount;
        BigDecimal totalPrincipal = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;

        int earlyRepaymentIndex = 0;

        for (int month = 1; month <= termMonths; month++) {
            // Early repayments are applied before the regular monthly payment.
            while (earlyRepaymentIndex < earlyRepayments.size()
                    && earlyRepayments.get(earlyRepaymentIndex).month() == month) {

                EarlyRepayment earlyRepayment =
                        earlyRepayments.get(earlyRepaymentIndex);

                if (earlyRepayment.amount().compareTo(remaining) > 0) {
                    throw new IllegalArgumentException(
                            "Early repayment amount exceeds remaining balance at month "
                                    + month
                    );
                }

                remaining = remaining.subtract(earlyRepayment.amount())
                        .setScale(SCALE, ROUNDING);

                totalPrincipal = totalPrincipal.add(
                        earlyRepayment.amount()
                );

                if (earlyRepayment.mode() == EarlyRepaymentMode.REDUCE_PAYMENT
                        && remaining.compareTo(BigDecimal.ZERO) > 0) {

                    int remainingMonths = termMonths - month + 1;

                    monthlyPayment = calculateMonthlyPayment(
                            remaining,
                            monthlyRate,
                            remainingMonths,
                            PaymentType.ANNUITY
                    );
                }

                earlyRepaymentIndex++;
            }

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal interestPayment = remaining.multiply(monthlyRate)
                    .setScale(SCALE, ROUNDING);

            BigDecimal principalPayment = monthlyPayment
                    .subtract(interestPayment)
                    .setScale(SCALE, ROUNDING);

            BigDecimal actualPayment = monthlyPayment;

            // Close the debt at maturity or when a regular payment covers it.
            if (month == termMonths
                    || principalPayment.compareTo(remaining) >= 0) {

                principalPayment = remaining.setScale(SCALE, ROUNDING);

                actualPayment = principalPayment.add(interestPayment)
                        .setScale(SCALE, ROUNDING);
            }

            remaining = remaining.subtract(principalPayment)
                    .setScale(SCALE, ROUNDING);

            totalPrincipal = totalPrincipal.add(principalPayment);
            totalInterest = totalInterest.add(interestPayment);

            schedule.add(new ScheduleEntry(
                    month,
                    actualPayment.setScale(SCALE, ROUNDING),
                    principalPayment,
                    interestPayment,
                    remaining
            ));

            if (remaining.compareTo(BigDecimal.ZERO) == 0) {
                break;
            }
        }

        BigDecimal overpayment = totalInterest.setScale(SCALE, ROUNDING);

        // Includes both regular principal payments and early repayments.
        BigDecimal totalPayment = totalPrincipal.add(totalInterest)
                .setScale(SCALE, ROUNDING);

        return new CalculationResponse(
                monthlyPayment.setScale(SCALE, ROUNDING),
                overpayment,
                totalPayment,
                schedule
        );
    }

    private CalculationResponse calculateDifferential(
            BigDecimal loanAmount,
            BigDecimal monthlyRate,
            int termMonths,
            List<EarlyRepayment> earlyRepayments
    ) {
        List<ScheduleEntry> schedule = new ArrayList<>();

        BigDecimal remaining = loanAmount;

        BigDecimal fixedPrincipal = loanAmount.divide(
                BigDecimal.valueOf(termMonths),
                SCALE,
                ROUNDING
        );

        BigDecimal totalPrincipal = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;

        int earlyRepaymentIndex = 0;

        for (int month = 1; month <= termMonths; month++) {
            // Early repayments are applied before the regular monthly payment.
            while (earlyRepaymentIndex < earlyRepayments.size()
                    && earlyRepayments.get(earlyRepaymentIndex).month() == month) {

                EarlyRepayment earlyRepayment =
                        earlyRepayments.get(earlyRepaymentIndex);

                if (earlyRepayment.amount().compareTo(remaining) > 0) {
                    throw new IllegalArgumentException(
                            "Early repayment amount exceeds remaining balance at month "
                                    + month
                    );
                }

                remaining = remaining.subtract(earlyRepayment.amount())
                        .setScale(SCALE, ROUNDING);

                totalPrincipal = totalPrincipal.add(
                        earlyRepayment.amount()
                );

                if (earlyRepayment.mode() == EarlyRepaymentMode.REDUCE_PAYMENT
                        && remaining.compareTo(BigDecimal.ZERO) > 0) {

                    int remainingMonths = termMonths - month + 1;

                    fixedPrincipal = remaining.divide(
                            BigDecimal.valueOf(remainingMonths),
                            SCALE,
                            ROUNDING
                    );
                }

                earlyRepaymentIndex++;
            }

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal interestPayment = remaining.multiply(monthlyRate)
                    .setScale(SCALE, ROUNDING);

            BigDecimal principalPayment = fixedPrincipal;

            if (month == termMonths
                    || principalPayment.compareTo(remaining) >= 0) {

                principalPayment = remaining.setScale(SCALE, ROUNDING);
            }

            BigDecimal actualPayment = principalPayment.add(interestPayment)
                    .setScale(SCALE, ROUNDING);

            remaining = remaining.subtract(principalPayment)
                    .setScale(SCALE, ROUNDING);

            totalPrincipal = totalPrincipal.add(principalPayment);
            totalInterest = totalInterest.add(interestPayment);

            schedule.add(new ScheduleEntry(
                    month,
                    actualPayment,
                    principalPayment.setScale(SCALE, ROUNDING),
                    interestPayment,
                    remaining
            ));

            if (remaining.compareTo(BigDecimal.ZERO) == 0) {
                break;
            }
        }

        BigDecimal overpayment = totalInterest.setScale(SCALE, ROUNDING);

        BigDecimal totalPayment = totalPrincipal.add(totalInterest)
                .setScale(SCALE, ROUNDING);

        BigDecimal firstPayment = schedule.isEmpty()
                ? BigDecimal.ZERO.setScale(SCALE, ROUNDING)
                : schedule.get(0).payment();

        return new CalculationResponse(
                firstPayment,
                overpayment,
                totalPayment,
                schedule
        );
    }

    private BigDecimal calculateMonthlyPayment(
            BigDecimal loanAmount,
            BigDecimal monthlyRate,
            int termMonths,
            PaymentType type
    ) {
        if (type == PaymentType.DIFFERENTIAL) {
            BigDecimal fixedPrincipal = loanAmount.divide(
                    BigDecimal.valueOf(termMonths),
                    SCALE,
                    ROUNDING
            );

            BigDecimal firstInterest = loanAmount.multiply(monthlyRate)
                    .setScale(SCALE, ROUNDING);

            return fixedPrincipal.add(firstInterest)
                    .setScale(SCALE, ROUNDING);
        }

        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return loanAmount.divide(
                    BigDecimal.valueOf(termMonths),
                    SCALE,
                    ROUNDING
            );
        }

        MathContext highPrecision =
                new MathContext(50, RoundingMode.HALF_UP);

        BigDecimal onePlusRate = BigDecimal.ONE.add(monthlyRate);
        BigDecimal power = onePlusRate.pow(termMonths, highPrecision);

        BigDecimal numerator = loanAmount.multiply(monthlyRate)
                .multiply(power);

        BigDecimal denominator = power.subtract(BigDecimal.ONE);

        return numerator.divide(denominator, SCALE, ROUNDING);
    }
}







