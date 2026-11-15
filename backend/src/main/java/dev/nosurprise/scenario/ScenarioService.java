package dev.nosurprise.scenario;

import dev.nosurprise.calc.service.CalculatorService;
import dev.nosurprise.calc.service.CalculationReadModel;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.CalcResponse;
import dev.nosurprise.offer.BankOffer;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.offer.web.dto.OfferResponse;
import dev.nosurprise.scenario.ScenarioExportData;
import dev.nosurprise.pdf.event.ExportPdfRequested;
import dev.nosurprise.scenario.ScenarioRecalculationResult;
import dev.nosurprise.scenario.web.dto.CreateScenarioRequest;
import dev.nosurprise.scenario.web.dto.ScenarioResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final Validator validator;

    public ScenarioService(ScenarioRepository repository, CalculatorService calculatorService,
                           OfferService offerService, ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher, Validator validator) {
        this.repository = repository;
        this.calculatorService = calculatorService;
        this.offerService = offerService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<ScenarioResponse> list(UUID userId) {
        return repository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ScenarioResponse create(UUID userId, CreateScenarioRequest request) {
        BankOffer offer = null;
        if (request.offerId() != null) {
            try {
                offer = offerService.findActiveEntity(request.offerId());
            } catch (dev.nosurprise.offer.OfferNotFoundException ex) {
                throw new InvalidOfferException("offer not found or inactive: " + request.offerId());
            }
        }

        CalcRequest calcRequest;
        try {
            calcRequest = objectMapper.treeToValue(request.params(), CalcRequest.class);
        } catch (Exception ex) {
            throw new InvalidRequestException("invalid scenario params: unparseable");
        }
        validate(calcRequest);

        if (offer != null) {
            validateAgainstOffer(calcRequest, offer);
        }

        CalcResponse calcResponse = calculatorService.calculate(calcRequest, userId);
        if (calcResponse.calcId() == null) {
            throw new IllegalStateException("calculation did not return calcId");
        }

        String paramsJson;
        String summaryJson;
        try {
            paramsJson = objectMapper.writeValueAsString(request.params());
            summaryJson = objectMapper.writeValueAsString(withoutSchedule(objectMapper.valueToTree(calcResponse)));
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
        // AFTER_COMMIT-слушатель (ScenarioPdfCleanupListener) чистит GridFS и PDF_READY-уведомления.
        eventPublisher.publishEvent(new dev.nosurprise.scenario.event.ScenarioDeleted(
                UUID.randomUUID(), Instant.now(), userId, scenarioId));
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

    /**
     * Проверяет владение сценарием (§6.2); чужой или отсутствующий — один и тот же ответ 404.
     */
    @Transactional(readOnly = true)
    public void assertOwned(UUID userId, UUID scenarioId) {
        findOwned(userId, scenarioId);
    }

    private Scenario findOwned(UUID userId, UUID scenarioId) {
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
            return Optional.of(findOwned(userId, scenarioId));
        } catch (ScenarioNotFoundException ex) {
            return Optional.empty();
        }
    }

    @Transactional
    public void requestExport(UUID userId, UUID scenarioId) {
        Scenario scenario = findOwned(userId, scenarioId);
        eventPublisher.publishEvent(new ExportPdfRequested(
                UUID.randomUUID(), Instant.now(), scenario.getId(), scenario.getCalcId(), userId));
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
        validate(newRequest);

        CalcResponse newResponse = calculatorService.calculate(newRequest, scenario.getUserId());

        String paramsJson;
        String summaryJson;
        try {
            paramsJson = objectMapper.writeValueAsString(newRequest);
            summaryJson = objectMapper.writeValueAsString(withoutSchedule(objectMapper.valueToTree(newResponse)));
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

    @Transactional(readOnly = true)
    public ScenarioExportData findScenarioExportData(UUID userId, UUID scenarioId) {
        Scenario scenario = findOwned(userId, scenarioId);
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

    /** Bean-validation контракта CalcRequest (§4): аннотации DTO применяются и вне HTTP-границы. */
    private void validate(CalcRequest calcRequest) {
        var violations = validator.validate(calcRequest);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .distinct()
                    .sorted()
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("invalid scenario params");
            throw new InvalidRequestException(detail);
        }
    }

    /** Параметры сценария не могут расходиться с предложением (§8): ставка, первый взнос, срок. */
    private void validateAgainstOffer(CalcRequest calcRequest, BankOffer offer) {
        if (offer.getRate() != null && calcRequest.annualRate() != null
                && offer.getRate().compareTo(calcRequest.annualRate()) != 0) {
            throw new InvalidRequestException("scenario annual rate " + calcRequest.annualRate().toPlainString()
                    + " does not match offer rate " + offer.getRate().toPlainString());
        }
        if (offer.getMinDownPaymentPercent() != null && calcRequest.price() != null
                && calcRequest.downPayment() != null && calcRequest.price().signum() > 0) {
            BigDecimal percent = calcRequest.downPayment()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(calcRequest.price(), 4, RoundingMode.HALF_UP);
            if (percent.compareTo(offer.getMinDownPaymentPercent()) < 0) {
                throw new InvalidRequestException("down payment " + percent.toPlainString()
                        + "% is below offer minimum " + offer.getMinDownPaymentPercent().toPlainString() + "%");
            }
        }
        if (offer.getMaxTermMonths() != null && calcRequest.termMonths() != null
                && calcRequest.termMonths() > offer.getMaxTermMonths()) {
            throw new InvalidRequestException("term " + calcRequest.termMonths()
                    + " months exceeds offer maximum " + offer.getMaxTermMonths());
        }
    }

    /** Убирает график платежей (до 360 строк) из summary — он доступен по calcId (§6.2, §8). */
    private JsonNode withoutSchedule(JsonNode summary) {
        if (summary instanceof ObjectNode objectNode) {
            objectNode.remove("schedule");
        }
        return summary;
    }

    private ScenarioResponse toResponse(Scenario scenario) {
        try {
            JsonNode params = objectMapper.readTree(scenario.getParams());
            JsonNode summary = withoutSchedule(objectMapper.readTree(scenario.getSummary()));
            return new ScenarioResponse(scenario.getId(), scenario.getName(), params, summary,
                    scenario.getCalcId(), scenario.getOfferId(), scenario.getCreatedAt(), scenario.getUpdatedAt());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to read scenario json", ex);
        }
    }
}
