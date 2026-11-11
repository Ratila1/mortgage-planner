package dev.nosurprise.scenario;

import dev.nosurprise.calc.service.CalculatorService;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.CalcResponse;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.scenario.web.dto.CreateScenarioRequest;
import dev.nosurprise.scenario.web.dto.ScenarioResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Service
public class ScenarioService {

    private final ScenarioRepository repository;
    private final CalculatorService calculatorService;
    private final OfferService offerService;
    private final ObjectMapper objectMapper;

    public ScenarioService(ScenarioRepository repository, CalculatorService calculatorService,
                           OfferService offerService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.calculatorService = calculatorService;
        this.offerService = offerService;
        this.objectMapper = objectMapper;
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

        CalcResponse calcResponse = calculatorService.calculate(calcRequest);
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