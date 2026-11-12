package dev.nosurprise.scenario;

import dev.nosurprise.calc.service.CalculatorService;
import dev.nosurprise.calc.service.CalculationReadModel;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.CalcResponse;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.offer.web.dto.OfferResponse;
import dev.nosurprise.scenario.ScenarioExportData;
import dev.nosurprise.pdf.event.ExportPdfRequested;
import dev.nosurprise.scenario.ScenarioRecalculationResult;
import dev.nosurprise.scenario.web.dto.CreateScenarioRequest;
import dev.nosurprise.scenario.web.dto.ScenarioResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ScenarioService {

    private final ScenarioRepository repository;
    private final CalculatorService calculatorService;
    private final OfferService offerService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ScenarioService(ScenarioRepository repository, CalculatorService calculatorService,
                           OfferService offerService, ObjectMapper objectMapper) {
        this(repository, calculatorService, offerService, objectMapper, null);
    }

    @Autowired
    public ScenarioService(ScenarioRepository repository, CalculatorService calculatorService,
                           OfferService offerService, ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.calculatorService = calculatorService;
        this.offerService = offerService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<ScenarioResponse> list(UUID userId) {
        return repository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ScenarioResponse create(UUID userId, CreateScenarioRequest request) {
        if (request.offerId() != null) {
            try {
                offerService.findActiveEntity(request.offerId());
            } catch (dev.nosurprise.offer.OfferNotFoundException ex) {
                throw new InvalidOfferException("offer not found or inactive: " + request.offerId());
            }
        }

        CalcRequest calcRequest;
        try {
            calcRequest = objectMapper.treeToValue(request.params(), CalcRequest.class);
        } catch (Exception ex) {
            throw new InvalidOfferException("invalid scenario params");
        }

        // С userId расчёт в Mongo живёт бессрочно; анонимный удалился бы через 7 дней (TTL),
        // и PDF сохранённого сценария падал бы с CALC_NOT_FOUND.
        CalcResponse calcResponse = calculatorService.calculate(calcRequest, userId);
        if (calcResponse.calcId() == null) {
            throw new IllegalStateException("calculation did not return calcId");
        }

        String paramsJson;
        String summaryJson;
        try {
            paramsJson = objectMapper.writeValueAsString(request.params());
            summaryJson = objectMapper.writeValueAsString(calcResponse);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize scenario", ex);
        }

        Scenario scenario = new Scenario(userId, request.name(), paramsJson, summaryJson,
                calcResponse.calcId(), request.offerId());
        Scenario saved = repository.save(scenario);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID userId, UUID scenarioId) {
        Scenario scenario = repository.findById(scenarioId)
                .orElseThrow(() -> new ScenarioNotFoundException("scenario not found: " + scenarioId));
        if (!scenario.getUserId().equals(userId)) {
            throw new ScenarioNotFoundException("scenario not found: " + scenarioId);
        }
        repository.delete(scenario);
    }

    @Transactional(readOnly = true)
    public List<ScenarioResponse> compare(UUID userId, List<UUID> ids) {
        if (ids.size() != 2 && ids.size() != 3) {
            throw new InvalidRequestException("compare requires 2 or 3 scenario ids");
        }
        List<Scenario> scenarios = repository.findAllByIdInAndUserId(ids, userId);
        if (scenarios.size() != ids.size()) {
            throw new ScenarioNotFoundException("scenario not found");
        }
        return scenarios.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Scenario findOwnedScenario(UUID userId, UUID scenarioId) {
        Scenario scenario = repository.findById(scenarioId)
                .orElseThrow(() -> new ScenarioNotFoundException("scenario not found: " + scenarioId));
        if (!scenario.getUserId().equals(userId)) {
            throw new ScenarioNotFoundException("scenario not found: " + scenarioId);
        }
        return scenario;
    }

    @Transactional(readOnly = true)
    public Optional<Scenario> findByIdAndUserId(UUID scenarioId, UUID userId) {
        try {
            return Optional.of(findOwnedScenario(userId, scenarioId));
        } catch (ScenarioNotFoundException ex) {
            return Optional.empty();
        }
    }

    @Transactional
    public void requestExport(UUID userId, UUID scenarioId) {
        Scenario scenario = findOwnedScenario(userId, scenarioId);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ExportPdfRequested(
                    UUID.randomUUID(), Instant.now(), scenario.getId(), scenario.getCalcId(), userId));
        }
    }

    @Transactional
    public ScenarioRecalculationResult recalculate(UUID scenarioId, BigDecimal newRate) {
        Scenario scenario = repository.findById(scenarioId)
                .orElseThrow(() -> new ScenarioNotFoundException("scenario not found: " + scenarioId));

        tools.jackson.databind.JsonNode oldParams;
        tools.jackson.databind.JsonNode oldSummary;
        try {
            oldParams = objectMapper.readTree(scenario.getParams());
            oldSummary = objectMapper.readTree(scenario.getSummary());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to read scenario json", ex);
        }

        BigDecimal oldRate = new BigDecimal(oldParams.get("annualRate").asText());
        BigDecimal oldMonthlyPayment = new BigDecimal(oldSummary.get("monthlyPayment").asText());
        BigDecimal oldOverpayment = new BigDecimal(oldSummary.get("overpayment").asText());

        CalcRequest oldRequest;
        try {
            oldRequest = objectMapper.treeToValue(oldParams, CalcRequest.class);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse scenario params", ex);
        }
        CalcRequest newRequest = new CalcRequest(
                oldRequest.price(), oldRequest.downPayment(), oldRequest.termMonths(),
                newRate, oldRequest.type(), oldRequest.earlyRepayments());

        CalcResponse newResponse = calculatorService.calculate(newRequest, scenario.getUserId());

        String paramsJson;
        String summaryJson;
        try {
            paramsJson = objectMapper.writeValueAsString(newRequest);
            summaryJson = objectMapper.writeValueAsString(newResponse);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize recalculated scenario", ex);
        }

        scenario.setParams(paramsJson);
        scenario.setSummary(summaryJson);
        scenario.setCalcId(newResponse.calcId());
        scenario.touch();
        repository.save(scenario);

        return new ScenarioRecalculationResult(
                scenario.getId(), scenario.getUserId(), newResponse.calcId(),
                oldRate, newRate, oldMonthlyPayment, new BigDecimal(newResponse.monthlyPayment()),
                oldOverpayment, new BigDecimal(newResponse.overpayment()));
    }

    @Transactional
    public void recalc(UUID scenarioId, BigDecimal newRate) {
        recalculate(scenarioId, newRate);
    }

    @Transactional(readOnly = true)
    public ScenarioExportData getScenarioExportData(UUID userId, UUID scenarioId) {
        return findScenarioExportData(userId, scenarioId);
    }

    @Transactional(readOnly = true)
    public ScenarioExportData findScenarioExportData(UUID userId, UUID scenarioId) {
        Scenario scenario = findOwnedScenario(userId, scenarioId);
        CalculationReadModel calculation = calculatorService.getCalculation(scenario.getCalcId());

        OfferResponse offer = null;
        if (scenario.getOfferId() != null) {
            try {
                offer = OfferResponse.from(offerService.findEntity(scenario.getOfferId()));
            } catch (RuntimeException ex) {
                offer = null;
            }
        }

        return new ScenarioExportData(
                scenario.getId(),
                scenario.getUserId(),
                scenario.getName(),
                calculation.params(),
                calculation.summary(),
                calculation.schedule(),
                calculation.calcId(),
                calculation.createdAt(),
                offer);
    }

    private ScenarioResponse toResponse(Scenario scenario) {
        try {
            JsonNode params = objectMapper.readTree(scenario.getParams());
            JsonNode summary = objectMapper.readTree(scenario.getSummary());
            return new ScenarioResponse(scenario.getId(), scenario.getName(), params, summary,
                    scenario.getCalcId(), scenario.getOfferId(), scenario.getCreatedAt(), scenario.getUpdatedAt());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to read scenario json", ex);
        }
    }
}