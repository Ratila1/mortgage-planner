package dev.nosurprise.mortgage.calculator;

import dev.nosurprise.mortgage.ratelimit.CacheService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Service
public class CalculatorServiceImpl implements CalculatorService {

    private static final MathContext MATH_CONTEXT = new MathContext(28, RoundingMode.HALF_UP);
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final long CACHE_TTL_SECONDS = 300; // 5 minutes

    private final CacheService cacheService;

    public CalculatorServiceImpl(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public Mono<CalculationResponse> calculate(CalculationRequest request) {
        // Validate early to fail fast before cache lookup
        request.validate();
        if (request.earlyRepayments() != null) {
            for (EarlyRepayment er : request.earlyRepayments()) {
                er.validate();
            }
        }

        String cacheKey = computeCacheKey(request);

        return cacheService.get(cacheKey)
                .switchIfEmpty(
                        Mono.fromCallable(() -> doCalculate(request))
                                .flatMap(response -> cacheService.put(cacheKey, response, CACHE_TTL_SECONDS)
                                        .thenReturn(response))
                );
    }

    private String computeCacheKey(CalculationRequest request) {
        try {
            String data = request.propertyPrice() + "|" +
                    request.downPayment() + "|" +
                    request.termMonths() + "|" +
                    request.rate() + "|" +
                    request.type().name() + "|" +
                    (request.earlyRepayments() != null ? request.earlyRepayments().toString() : "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes());
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private CalculationResponse doCalculate(CalculationRequest request) {
        BigDecimal loanAmount = request.loanAmount();
        BigDecimal monthlyRate = request.rate().divide(BigDecimal.valueOf(12), MATH_CONTEXT);
        int termMonths = request.termMonths();

        List<EarlyRepayment> earlyRepayments = request.earlyRepayments() != null
                ? new ArrayList<>(request.earlyRepayments())
                : new ArrayList<>();

        earlyRepayments.sort((a, b) -> Integer.compare(a.month(), b.month()));

        if (request.type() == PaymentType.DIFFERENTIAL) {
            return calculateDifferential(loanAmount, monthlyRate, termMonths, earlyRepayments);
        }

        BigDecimal monthlyPayment = calculateMonthlyPayment(loanAmount, monthlyRate, termMonths, request.type());
        return calculateAnnuity(loanAmount, monthlyRate, termMonths, monthlyPayment, earlyRepayments);
    }

    private CalculationResponse calculateAnnuity(BigDecimal loanAmount,
                                                   BigDecimal monthlyRate,
                                                   int termMonths,
                                                   BigDecimal monthlyPayment,
                                                   List<EarlyRepayment> earlyRepayments) {
        List<ScheduleEntry> schedule = new ArrayList<>();
        BigDecimal remaining = loanAmount;
        int earlyRepaymentIndex = 0;
        BigDecimal totalPrincipal = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;

        for (int month = 1; month <= termMonths; month++) {
            // Apply early repayments at the START of the month (before regular payment)
            while (earlyRepaymentIndex < earlyRepayments.size()
                    && earlyRepayments.get(earlyRepaymentIndex).month() == month) {
                EarlyRepayment er = earlyRepayments.get(earlyRepaymentIndex);
                if (er.amount().compareTo(remaining) > 0) {
                    throw new IllegalArgumentException(
                            "Early repayment amount exceeds remaining balance at month " + month);
                }

                if (er.mode() == EarlyRepaymentMode.REDUCE_TERM) {
                    remaining = remaining.subtract(er.amount()).setScale(SCALE, ROUNDING);
                    totalPrincipal = totalPrincipal.add(er.amount());
                } else { // REDUCE_PAYMENT
                    remaining = remaining.subtract(er.amount()).setScale(SCALE, ROUNDING);
                    int remainingMonths = termMonths - month + 1;
                    if (remainingMonths > 0 && remaining.compareTo(BigDecimal.ZERO) > 0) {
                        monthlyPayment = calculateMonthlyPayment(remaining, monthlyRate, remainingMonths, PaymentType.ANNUITY);
                    }
                    totalPrincipal = totalPrincipal.add(er.amount());
                }

                if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                    remaining = BigDecimal.ZERO;
                }

                earlyRepaymentIndex++;
            }

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal interestPayment = remaining.multiply(monthlyRate).setScale(SCALE, ROUNDING);
            BigDecimal principalPayment = monthlyPayment.subtract(interestPayment);

            // Adjust last payment to ensure remaining reaches exactly zero
            boolean isLastMonth = (month == termMonths) || (earlyRepaymentIndex >= earlyRepayments.size() && month == termMonths);
            if (principalPayment.compareTo(remaining) >= 0 || remaining.subtract(principalPayment).abs().compareTo(new BigDecimal("0.01")) < 0) {
                principalPayment = remaining.setScale(SCALE, ROUNDING);
                interestPayment = monthlyPayment.subtract(principalPayment);
                // If this is the last payment, adjust monthly payment to match
                if (principalPayment.compareTo(remaining) >= 0) {
                    monthlyPayment = principalPayment.add(interestPayment).setScale(SCALE, ROUNDING);
                }
            }

            remaining = remaining.subtract(principalPayment).setScale(SCALE, ROUNDING);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                remaining = BigDecimal.ZERO;
            }

            totalPrincipal = totalPrincipal.add(principalPayment);
            totalInterest = totalInterest.add(interestPayment);

            schedule.add(new ScheduleEntry(
                    month,
                    monthlyPayment.setScale(SCALE, ROUNDING),
                    principalPayment.setScale(SCALE, ROUNDING),
                    interestPayment.setScale(SCALE, ROUNDING),
                    remaining.setScale(SCALE, ROUNDING)
            ));

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
        }

        // Ensure last remaining is exactly zero by adjusting last entry if needed
        if (!schedule.isEmpty()) {
            ScheduleEntry last = schedule.get(schedule.size() - 1);
            if (last.remaining().compareTo(BigDecimal.ZERO) != 0 && last.remaining().abs().compareTo(new BigDecimal("1.00")) < 0) {
                // Adjust last entry to have zero remaining
                ScheduleEntry adjusted = new ScheduleEntry(
                        last.month(),
                        last.payment(),
                        last.principal(),
                        last.interest(),
                        BigDecimal.ZERO
                );
                schedule.set(schedule.size() - 1, adjusted);
            }
        }

        BigDecimal overpayment = totalInterest.setScale(SCALE, ROUNDING);
        BigDecimal totalPayment = totalPrincipal.add(totalInterest).setScale(SCALE, ROUNDING);

        if (!schedule.isEmpty()) {
            ScheduleEntry last = schedule.get(schedule.size() - 1);
            if (last.remaining().compareTo(BigDecimal.ZERO) == 0) {
                BigDecimal sumPrincipal = schedule.stream()
                        .map(ScheduleEntry::principal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal sumInterest = schedule.stream()
                        .map(ScheduleEntry::interest)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                overpayment = sumInterest.setScale(SCALE, ROUNDING);
                totalPayment = sumPrincipal.add(sumInterest).setScale(SCALE, ROUNDING);
            }
        }

        return new CalculationResponse(
                monthlyPayment.setScale(SCALE, ROUNDING),
                overpayment,
                totalPayment,
                schedule
        );
    }

    private CalculationResponse calculateDifferential(BigDecimal loanAmount,
                                                        BigDecimal monthlyRate,
                                                        int termMonths,
                                                        List<EarlyRepayment> earlyRepayments) {
        List<ScheduleEntry> schedule = new ArrayList<>();
        BigDecimal remaining = loanAmount;
        BigDecimal fixedPrincipal = loanAmount.divide(BigDecimal.valueOf(termMonths), SCALE, ROUNDING);
        int earlyRepaymentIndex = 0;
        BigDecimal totalPrincipal = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;

        for (int month = 1; month <= termMonths; month++) {
            // Apply early repayments at the START of the month
            while (earlyRepaymentIndex < earlyRepayments.size()
                    && earlyRepayments.get(earlyRepaymentIndex).month() == month) {
                EarlyRepayment er = earlyRepayments.get(earlyRepaymentIndex);
                if (er.amount().compareTo(remaining) > 0) {
                    throw new IllegalArgumentException(
                            "Early repayment amount exceeds remaining balance at month " + month);
                }

                remaining = remaining.subtract(er.amount()).setScale(SCALE, ROUNDING);

                if (er.mode() == EarlyRepaymentMode.REDUCE_PAYMENT) {
                    // REDUCE_PAYMENT: recalculate fixed principal for remaining months of original term
                    int remainingMonths = termMonths - month + 1;
                    if (remainingMonths > 0 && remaining.compareTo(BigDecimal.ZERO) > 0) {
                        fixedPrincipal = remaining.divide(BigDecimal.valueOf(remainingMonths), SCALE, ROUNDING);
                    }
                }
                // REDUCE_TERM: keep fixedPrincipal unchanged, term will naturally reduce

                totalPrincipal = totalPrincipal.add(er.amount());

                if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                    remaining = BigDecimal.ZERO;
                }

                earlyRepaymentIndex++;
            }

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal interestPayment = remaining.multiply(monthlyRate).setScale(SCALE, ROUNDING);
            BigDecimal principalPayment = fixedPrincipal;

            if (month == termMonths || principalPayment.compareTo(remaining) >= 0) {
                principalPayment = remaining.setScale(SCALE, ROUNDING);
            }

            BigDecimal monthlyPayment = principalPayment.add(interestPayment).setScale(SCALE, ROUNDING);
            remaining = remaining.subtract(principalPayment).setScale(SCALE, ROUNDING);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                remaining = BigDecimal.ZERO;
            }

            totalPrincipal = totalPrincipal.add(principalPayment);
            totalInterest = totalInterest.add(interestPayment);

            schedule.add(new ScheduleEntry(
                    month,
                    monthlyPayment,
                    principalPayment.setScale(SCALE, ROUNDING),
                    interestPayment,
                    remaining
            ));

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
        }

        BigDecimal overpayment = totalInterest.setScale(SCALE, ROUNDING);
        BigDecimal totalPayment = totalPrincipal.add(totalInterest).setScale(SCALE, ROUNDING);

        BigDecimal firstPayment = schedule.isEmpty() ? BigDecimal.ZERO : schedule.get(0).payment();

        return new CalculationResponse(
                firstPayment,
                overpayment,
                totalPayment,
                schedule
        );
    }

    private BigDecimal calculateMonthlyPayment(BigDecimal loanAmount, BigDecimal monthlyRate, int termMonths, PaymentType type) {
        if (type == PaymentType.DIFFERENTIAL) {
            BigDecimal fixedPrincipal = loanAmount.divide(BigDecimal.valueOf(termMonths), SCALE, ROUNDING);
            BigDecimal firstInterest = loanAmount.multiply(monthlyRate).setScale(SCALE, ROUNDING);
            return fixedPrincipal.add(firstInterest).setScale(SCALE, ROUNDING);
        }

        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return loanAmount.divide(BigDecimal.valueOf(termMonths), SCALE, ROUNDING);
        }

        // Use higher precision for power calculation to avoid rounding errors for long terms
        MathContext highPrecision = new MathContext(50, RoundingMode.HALF_UP);
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal pow = onePlusR.pow(termMonths, highPrecision);
        BigDecimal numerator = loanAmount.multiply(monthlyRate).multiply(pow);
        BigDecimal denominator = pow.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, SCALE, ROUNDING);
    }
}







