package com.bank.mortgage.user;

import com.bank.mortgage.calculator.CalculationRequest;
import com.bank.mortgage.calculator.CalculationResponse;
import com.bank.mortgage.calculator.EarlyRepayment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

public interface ScenarioService {
    Mono<Scenario> saveScenario(Long userId, String name, CalculationRequest request, CalculationResponse response);
    Flux<Scenario> getUserScenarios(Long userId);
    Mono<Scenario> getScenario(Long userId, String scenarioId);
    Mono<Void> deleteScenario(Long userId, String scenarioId);
    Mono<ComparisonResult> compareScenarios(Long userId, List<String> scenarioIds);
}