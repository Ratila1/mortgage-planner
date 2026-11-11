package com.bank.mortgage.user;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface ScenarioRepository extends ReactiveMongoRepository<Scenario, String> {

    @Query("{ 'userId': ?0 }")
    Flux<Scenario> findByUserId(Long userId);

    @Query("{ 'userId': ?0, '_id': { $in: ?1 } }")
    Flux<Scenario> findByUserIdAndIds(Long userId, List<String> ids);

    Mono<Scenario> findByIdAndUserId(String id, Long userId);

    // For rate change notifications - find all scenarios with a specific rate
    @Query("{ 'rate': ?0 }")
    Flux<Scenario> findByRate(java.math.BigDecimal rate);
}