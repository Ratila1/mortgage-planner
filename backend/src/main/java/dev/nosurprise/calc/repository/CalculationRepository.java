package dev.nosurprise.calc.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * История расчётов живёт на индексе (userId, createdAt desc) — он создается в CalculationIndexes.
 */
public interface CalculationRepository extends MongoRepository<CalculationDocument, String> {

    Optional<CalculationDocument> findByParamsHash(String paramsHash);
}
