package dev.nosurprise.calc.cache;

import java.util.Optional;

/**
 * Порт кэша расчётов (§6.3): (ownerScope, paramsHash) → calcId.
 * ownerScope изолирует владельцев: пользователь не должен получать по общему paramsHash
 * calcId чужого (или анонимного) расчёта — иначе через кэш утекают чужие данные.
 * Анонимные расчёты шарят один scope "anon": их документы публичны и сами по себе
 * утечки не создают, а повторные анонимные запросы продолжают переиспользовать расчёт.
 * Формат ключа (`calc:{ownerScope}:{paramsHash}`) и TTL (1 ч) — забота реализации.
 * Реализация должна быть отказоустойчивой с точки зрения сервиса: недоступный кэш
 * не должен ломать расчёт (сервис глотает RuntimeException и логирует warning).
 */
public interface CalcCache {

    /** calcId по ownerScope+paramsHash или empty при промахе. */
    Optional<String> findCalcId(String ownerScope, String paramsHash);

    /** Сохраняет отображение (ownerScope, paramsHash) → calcId (TTL 1 ч, §6.3). */
    void save(String ownerScope, String paramsHash, String calcId);

    /** Удаляет устаревшую запись (например, когда документа в Mongo уже нет). */
    void evict(String ownerScope, String paramsHash);
}
