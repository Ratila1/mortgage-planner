package dev.nosurprise.mortgage.calculator;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class FloatingRateServiceImpl implements FloatingRateService {

    private final CalculatorService calculatorService;

    public FloatingRateServiceImpl(CalculatorService calculatorService) {
        this.calculatorService = calculatorService;
    }

    @Override
    public Mono<CalculationResponse> calculateWithRateIncrease(CalculationRequest baseRequest, int increasePercent) {
        if (increasePercent < 0) {
            return Mono.error(new IllegalArgumentException("Increase percent must be non-negative"));
        }

        BigDecimal increase = BigDecimal.valueOf(increasePercent).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        BigDecimal newRate = baseRequest.rate().add(increase);

        CalculationRequest newRequest = new CalculationRequest(
                baseRequest.propertyPrice(),
                baseRequest.downPayment(),
                baseRequest.termMonths(),
                newRate,
                baseRequest.type(),
                baseRequest.earlyRepayments()
        );

        return calculatorService.calculate(newRequest);
    }
}







