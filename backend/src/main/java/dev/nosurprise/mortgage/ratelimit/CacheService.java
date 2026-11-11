package dev.nosurprise.mortgage.ratelimit;

import dev.nosurprise.mortgage.calculator.CalculationResponse;
import reactor.core.publisher.Mono;

public interface CacheService {
    Mono<CalculationResponse> get(String key);
    Mono<Void> put(String key, CalculationResponse value, long ttlSeconds);
}







