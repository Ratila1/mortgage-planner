package com.bank.mortgage.calculator;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class EarlyRepaymentServiceImpl implements EarlyRepaymentService {

    private final CalculatorService calculatorService;

    public EarlyRepaymentServiceImpl(CalculatorService calculatorService) {
        this.calculatorService = calculatorService;
    }

    @Override
    public Mono<CalculationResponse> recalculateWithEarlyRepayments(CalculationRequest originalRequest,
                                                                     List<EarlyRepayment> earlyRepayments) {
        CalculationRequest newRequest = new CalculationRequest(
                originalRequest.propertyPrice(),
                originalRequest.downPayment(),
                originalRequest.termMonths(),
                originalRequest.rate(),
                originalRequest.type(),
                earlyRepayments
        );
        return calculatorService.calculate(newRequest);
    }
}