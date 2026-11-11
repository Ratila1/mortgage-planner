package dev.nosurprise.calc.cache;

import java.util.Optional;

/**
 * Порт кэша расчётов (§6.3): paramsHash → calcId.
 * Формат ключа (`calc:{paramsHash}`) и TTL (1 ч) — забота реализации, интерфейс их не раскрывает.
 * Реализация должна быть отказоустойчивой с точки зрения сервиса: недоступный кэш
 * не должен ломать расчёт (сервис глотает RuntimeException и логирует warning).
 */
public interface CalcCache {

    /** calcId по paramsHash или empty при промахе. */
    Optional<String> findCalcId(String paramsHash);

    /** Сохраняет отображение paramsHash → calcId (TTL 1 ч, §6.3). */
    void save(String paramsHash, String calcId);

    /** Удаляет устаревшую запись (например, когда документа в Mongo уже нет). */
    void evict(String paramsHash);
}
