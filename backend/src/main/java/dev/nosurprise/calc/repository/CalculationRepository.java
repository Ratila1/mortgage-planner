package dev.nosurprise.calc.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * История расчётов живёт на индексе (userId, createdAt desc) — он создается в CalculationIndexes.
 * Идентичность расчёта — пара (paramsHash, userId): один и тот же paramsHash может
 * существовать и как анонимный документ (userId = null), и у каждого пользователя.
 */
public interface CalculationRepository extends MongoRepository<CalculationDocument, String> {

    /**
     * Расчёт владельца по paramsHash. null в качестве userId матчится на документы
     * с отсутствующим/null userId (анонимные) — как Mongo-семантика {field: null}.
     */
    Optional<CalculationDocument> findByParamsHashAndUserId(String paramsHash, String userId);

    /** Последние 50 расчётов пользователя для GET /api/calc/history (§9). */
    List<CalculationDocument> findTop50ByUserIdOrderByCreatedAtDesc(String userId);
}
