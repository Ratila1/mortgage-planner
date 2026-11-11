package com.bank.mortgage.ratelimit;

import com.bank.mortgage.calculator.CalculationResponse;
import reactor.core.publisher.Mono;

public interface CacheService {
    Mono<CalculationResponse> get(String key);
    Mono<Void> put(String key, CalculationResponse value, long ttlSeconds);
}