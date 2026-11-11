package com.bank.mortgage.calculator;

import reactor.core.publisher.Mono;

public interface FloatingRateService {
    Mono<CalculationResponse> calculateWithRateIncrease(CalculationRequest baseRequest, int increasePercent);
}