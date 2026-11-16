package dev.nosurprise.calc.service;

import dev.nosurprise.calc.cache.CalcCache;
import dev.nosurprise.calc.cache.RedisCalcCache;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Слой service: валидация-преобразование → paramsHash → Redis-кэш → Mongo → расчёт ядром
 * → сохранение (§6.2, §6.3).
 * Расчёты скоупятся по владельцу (§6.2): идентичность — пара (paramsHash, userId).
 * Порядок: Redis calc:{ownerScope}:{paramsHash} → calcId, по calcId — документ в Mongo;
 * промах Redis → поиск в Mongo по (paramsHash, userId); полный miss — расчёт, сохранение,
 * запись в кэш. «Усыновление» анонимного документа пользователем убрано: анонимный
 * расчёт остаётся анонимным (TTL 7 дней), пользователь получает свой документ.
 * Сбои Redis (findCalcId/save/evict) не ломают расчёт: warning и продолжение как cache miss.
 * Гонка двух одинаковых POST ловится уникальным индексом (paramsHash, userId):
 * DuplicateKeyException → возврат уже сохранённого документа.
 */
@Service
public class CalculatorService {

    private static final Logger log = LoggerFactory.getLogger(CalculatorService.class);

    /** TTL анонимных расчётов (§6.2). */
    static final Duration ANONYMOUS_TTL = Duration.ofDays(7);

    private final CalculationRepository repository;
    private final CalcCache cache;
    private final Clock clock;

    public CalculatorService(CalculationRepository repository, CalcCache cache, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.clock = clock;
    }

    public CalcResponse calculate(CalcRequest request, UUID userId) {
        LoanParams params = CalcRequestMapper.toParams(request);
        String paramsHash = ParamsHasher.hash(params);
        String ownerScope = ownerScope(userId);
        CalcResponse cached = fromCache(ownerScope, paramsHash);
        if (cached != null) {
            return cached;
        }
        CalculationDocument existing = repository
                .findByParamsHashAndUserId(paramsHash, userId == null ? null : userId.toString())
                .orElse(null);
        if (existing != null) {
            warmCache(ownerScope, paramsHash, existing.id());
            return toResponse(existing);
        }
        CalculationResult result = new MortgageCalculator().calculate(params);
        Instant now = clock.instant();
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
        CalculationDocument saved;
        try {
            saved = repository.save(document);
        } catch (DuplicateKeyException ex) {
            // Гонка: параллельный запрос уже сохранил ту же пару (paramsHash, userId).
            CalculationDocument winner = repository
                    .findByParamsHashAndUserId(paramsHash, userId == null ? null : userId.toString())
                    .orElseThrow(() -> ex);
            warmCache(ownerScope, paramsHash, winner.id());
            return toResponse(winner);
        }
        warmCache(ownerScope, paramsHash, saved.id());
        return toResponse(saved);
    }

    /**
     * Расчёт по calcId с проверкой владения (§6.2): документ пользователя виден только
     * владельцу (чужой и отсутствующий — один и тот же 404, чтобы не раскрывать существование);
     * анонимный документ (userId = null) публичен — на него ведут публичные расчёты и сценарии.
     */
    public CalcResponse get(String calcId, UUID viewer) {
        CalculationDocument document = findOwned(calcId, viewer);
        return toResponse(document);
    }

    /** История расчётов пользователя (§9), без графика платежей. Аноним — пустой список. */
    public List<CalcResponse> history(UUID viewer) {
        if (viewer == null) {
            return List.of();
        }
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(viewer.toString()).stream()
                .map(document -> new CalcResponse(
                        document.id(),
                        Objects.requireNonNull(document.params(), "params is required").loanAmount().toPlainString(),
                        document.summary().monthlyPayment().toPlainString(),
                        document.summary().overpayment().toPlainString(),
                        document.summary().totalPaid().toPlainString(),
                        document.summary().termMonths(),
                        null,
                        null))
                .toList();
    }

    /** Внутренний доступ без проверки владельца — вызывается только после проверки сценария. */
    public CalculationReadModel findCalculation(String calcId) {
        CalculationDocument document = loadDocument(calcId);
        return new CalculationReadModel(document.id(), document.params(), document.summary(),
                document.schedule(), document.createdAt(), document.userId());
    }

    private CalculationDocument findOwned(String calcId, UUID viewer) {
        CalculationDocument document = loadDocument(calcId);
        String owner = document.userId();
        if (owner == null) {
            return document; // анонимные расчёты публичны
        }
        if (viewer == null || !owner.equals(viewer.toString())) {
            throw new CalcNotFoundException("calculation not found: " + calcId);
        }
        return document;
    }

    private CalculationDocument loadDocument(String calcId) {
        if (calcId == null || !ObjectId.isValid(calcId)) {
            throw new CalcNotFoundException("calculation not found: " + calcId);
        }
        return repository.findById(calcId)
                .orElseThrow(() -> new CalcNotFoundException("calculation not found: " + calcId));
    }

    private static String ownerScope(UUID userId) {
        return userId == null ? RedisCalcCache.ANONYMOUS_SCOPE : userId.toString();
    }

    /** Redis → документ по calcId. null — нет в кэше или запись устарела (документ удалён). */
    private CalcResponse fromCache(String ownerScope, String paramsHash) {
        String calcId;
        try {
            calcId = cache.findCalcId(ownerScope, paramsHash).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("calc cache lookup failed, falling back to Mongo: {}", paramsHash, ex);
            return null;
        }
        if (calcId == null) {
            return null;
        }
        CalculationDocument document = repository.findById(calcId).orElse(null);
        if (document != null) {
            return toResponse(document);
        }
        // Redis опережает Mongo: запись устарела — удаляем и считаем как cache miss.
        try {
            cache.evict(ownerScope, paramsHash);
        } catch (RuntimeException ex) {
            log.warn("calc cache evict failed for stale entry: {}", paramsHash, ex);
        }
        return null;
    }

    /** Прогрев/запись кэша после того, как документ уже есть в Mongo и отвечает пользователю. */
    private void warmCache(String ownerScope, String paramsHash, String calcId) {
        if (calcId == null) {
            return;
        }
        try {
            cache.save(ownerScope, paramsHash, calcId);
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
