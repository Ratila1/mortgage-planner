package dev.nosurprise.mortgage.calculator;

import reactor.core.publisher.Mono;

public interface CalculatorService {
    Mono<CalculationResponse> calculate(CalculationRequest request);
}







