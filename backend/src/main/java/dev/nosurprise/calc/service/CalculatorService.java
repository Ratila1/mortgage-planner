package dev.nosurprise.calc.service;

import dev.nosurprise.calc.cache.CalcCache;
import dev.nosurprise.calc.repository.CalculationDocument;
import dev.nosurprise.calc.repository.CalculationRepository;
import dev.nosurprise.calc.web.CalcRequestMapper;
import dev.nosurprise.calc.web.CalcResponseMapper;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.CalcResponse;
import dev.nosurprise.calculator.CalculationResult;
import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.MortgageCalculator;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Слой service: валидация-преобразование → paramsHash → Redis-кэш → Mongo → расчёт ядром
 * → сохранение (§6.2, §6.3).
 * Порядок: Redis calc:{paramsHash} → calcId, по calcId — документ в Mongo; промах Redis →
 * поиск в Mongo по paramsHash (с прогревом кэша); полный miss — расчёт, сохранение, запись в кэш.
 * Повторный расчёт с тем же paramsHash не создаёт новый документ — возвращается существующий calcId.
 * Сбои Redis (findCalcId/save/evict) не ломают расчёт: warning и продолжение как cache miss.
 * Пользовательской аутентификации ещё нет: все расчёты анонимные → expireAt = now + 7 дней.
 */
@Service
public class CalculatorService {

    private static final Logger log = LoggerFactory.getLogger(CalculatorService.class);

    /** TTL анонимных расчётов (§6.2). */
    static final Duration ANONYMOUS_TTL = Duration.ofDays(7);

    private final CalculationRepository repository;
    private final CalcCache cache;

    public CalculatorService(CalculationRepository repository, CalcCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public CalcResponse calculate(CalcRequest request) {
        return calculate(request, null);
    }

    public CalcResponse calculate(CalcRequest request, UUID userId) {
        LoanParams params = CalcRequestMapper.toParams(request);
        String paramsHash = ParamsHasher.hash(params);
        CalcResponse cached = fromCache(paramsHash, userId);
        if (cached != null) {
            return cached;
        }
        CalculationDocument existing = repository.findByParamsHash(paramsHash).orElse(null);
        if (existing != null) {
            if (userId != null && existing.userId() == null) {
                CalculationDocument updated = new CalculationDocument(
                        existing.id(),
                        existing.paramsHash(),
                        userId == null ? null : userId.toString(),
                        existing.params(),
                        existing.summary(),
                        existing.savings(),
                        existing.schedule(),
                        existing.createdAt(),
                        null);
                repository.save(updated);
                warmCache(paramsHash, updated.id());
                return toResponse(updated);
            }
            warmCache(paramsHash, existing.id());
            return toResponse(existing);
        }
        CalculationResult result = new MortgageCalculator().calculate(params);
        Instant now = Instant.now();
        CalculationDocument document = new CalculationDocument(
                null,
                paramsHash,
                userId == null ? null : userId.toString(),
                params,
                result.summary(),
                result.savings(),
                result.schedule(),
                now,
                userId == null ? now.plus(ANONYMOUS_TTL) : null);
        CalculationDocument saved = repository.save(document);
        warmCache(paramsHash, saved.id());
        return toResponse(saved);
    }

    public CalcResponse get(String calcId) {
        if (calcId == null || !ObjectId.isValid(calcId)) {
            throw new CalcNotFoundException("calculation not found: " + calcId);
        }
        CalculationDocument document = repository.findById(calcId)
                .orElseThrow(() -> new CalcNotFoundException("calculation not found: " + calcId));
        return toResponse(document);
    }

    public CalculationReadModel getCalculation(String calcId) {
        return findCalculation(calcId);
    }

    public CalculationReadModel findCalculation(String calcId) {
        if (calcId == null || !ObjectId.isValid(calcId)) {
            throw new CalcNotFoundException("calculation not found: " + calcId);
        }
        CalculationDocument document = repository.findById(calcId)
                .orElseThrow(() -> new CalcNotFoundException("calculation not found: " + calcId));
        return new CalculationReadModel(document.id(), document.params(), document.summary(),
                document.schedule(), document.createdAt(), document.userId());
    }

    /** Redis → документ по calcId. null — нет в кэше или запись устарела (документ удалён). */
    private CalcResponse fromCache(String paramsHash, UUID userId) {
        String calcId;
        try {
            calcId = cache.findCalcId(paramsHash).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("calc cache lookup failed, falling back to Mongo: {}", paramsHash, ex);
            return null;
        }
        if (calcId == null) {
            return null;
        }
        CalculationDocument document = repository.findById(calcId).orElse(null);
        if (document != null) {
            if (userId != null && document.userId() == null) {
                CalculationDocument updated = new CalculationDocument(
                        document.id(),
                        document.paramsHash(),
                        userId == null ? null : userId.toString(),
                        document.params(),
                        document.summary(),
                        document.savings(),
                        document.schedule(),
                        document.createdAt(),
                        null);
                repository.save(updated);
                return toResponse(updated);
            }
            return toResponse(document);
        }
        // Redis опережает Mongo: запись устарела — удаляем и считаем как cache miss.
        try {
            cache.evict(paramsHash);
        } catch (RuntimeException ex) {
            log.warn("calc cache evict failed for stale entry: {}", paramsHash, ex);
        }
        return null;
    }

    /** Прогрев/запись кэша после того, как документ уже есть в Mongo и отвечает пользователю. */
    private void warmCache(String paramsHash, String calcId) {
        if (calcId == null) {
            return;
        }
        try {
            cache.save(paramsHash, calcId);
        } catch (RuntimeException ex) {
            log.warn("calc cache save failed: {}", paramsHash, ex);
        }
    }

    private CalcResponse toResponse(CalculationDocument document) {
        Objects.requireNonNull(document.summary(), "summary is required");
        Objects.requireNonNull(document.params(), "params is required");
        CalculationResult result = new CalculationResult(document.summary(), document.schedule(), document.savings());
        return CalcResponseMapper.toResponse(document.id(), document.params().loanAmount(), result);
    }
}
