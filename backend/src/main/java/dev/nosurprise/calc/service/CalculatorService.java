package dev.nosurprise.calc.service;

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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Слой service: валидация-преобразование → paramsHash → поиск существующего расчёта
 * в Mongo → расчёт ядром → сохранение (§6.2, §6.3).
 * Повторный расчёт с тем же paramsHash не создаёт новый документ — возвращается существующий calcId.
 * Пользовательской аутентификации ещё нет: все расчёты анонимные → expireAt = now + 7 дней.
 */
@Service
public class CalculatorService {

    /** TTL анонимных расчётов (§6.2). */
    static final Duration ANONYMOUS_TTL = Duration.ofDays(7);

    private final CalculationRepository repository;

    public CalculatorService(CalculationRepository repository) {
        this.repository = repository;
    }

    public CalcResponse calculate(CalcRequest request) {
        LoanParams params = CalcRequestMapper.toParams(request);
        String paramsHash = ParamsHasher.hash(params);
        CalculationDocument existing = repository.findByParamsHash(paramsHash).orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }
        CalculationResult result = new MortgageCalculator().calculate(params);
        Instant now = Instant.now();
        CalculationDocument document = new CalculationDocument(
                null,
                paramsHash,
                null, // userId появится с аутентификацией; все расчёты пока анонимные
                params,
                result.summary(),
                result.savings(),
                result.schedule(),
                now,
                now.plus(ANONYMOUS_TTL));
        return toResponse(repository.save(document));
    }

    public CalcResponse get(String calcId) {
        if (calcId == null || !ObjectId.isValid(calcId)) {
            throw new CalcNotFoundException("calculation not found: " + calcId);
        }
        CalculationDocument document = repository.findById(calcId)
                .orElseThrow(() -> new CalcNotFoundException("calculation not found: " + calcId));
        return toResponse(document);
    }

    private CalcResponse toResponse(CalculationDocument document) {
        Objects.requireNonNull(document.summary(), "summary is required");
        Objects.requireNonNull(document.params(), "params is required");
        CalculationResult result = new CalculationResult(document.summary(), document.schedule(), document.savings());
        return CalcResponseMapper.toResponse(document.id(), document.params().loanAmount(), result);
    }
}
