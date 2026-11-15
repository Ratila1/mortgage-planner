package dev.nosurprise.scenario;

import dev.nosurprise.calc.service.CalculatorService;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.CalcResponse;
import dev.nosurprise.offer.BankOffer;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.scenario.web.dto.CreateScenarioRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private ScenarioService service(ScenarioRepository repository, CalculatorService calculatorService,
                                    OfferService offerService) {
        return new ScenarioService(repository, calculatorService, offerService, objectMapper,
                eventPublisher, validator);
    }

    @Test
    @DisplayName("create: выполняет расчёт, сохраняет сценарий с calcId")
    void createScenario() throws Exception {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = service(repository, calculatorService, offerService);

        UUID userId = UUID.randomUUID();
        JsonNode params = objectMapper.readTree("{\"price\":1000000,\"downPayment\":100000,\"termMonths\":12,\"annualRate\":12,\"type\":\"ANNUITY\",\"earlyRepayments\":[]}");
        CreateScenarioRequest request = new CreateScenarioRequest("Test", params, null);

        CalcResponse calcResponse = new CalcResponse("calc-1", "900000", "80000", "10000", "960000", 12, null, List.of());
        when(calculatorService.calculate(any(CalcRequest.class), any())).thenReturn(calcResponse);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(userId, request);

        assertThat(response.calcId()).isEqualTo("calc-1");
        assertThat(response.name()).isEqualTo("Test");
    }

    @Test
    @DisplayName("create: неактивный/несуществующий offer → InvalidOfferException")
    void createInvalidOffer() throws Exception {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = service(repository, calculatorService, offerService);

        UUID userId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        JsonNode params = objectMapper.readTree("{\"price\":1000000,\"downPayment\":100000,\"termMonths\":12,\"annualRate\":12,\"type\":\"ANNUITY\",\"earlyRepayments\":[]}");
        CreateScenarioRequest request = new CreateScenarioRequest("Test", params, offerId);

        when(offerService.findActiveEntity(offerId)).thenThrow(new dev.nosurprise.offer.OfferNotFoundException("not found"));

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(InvalidOfferException.class);
    }

    @Test
    @DisplayName("create: ставка сценария не совпадает с оффером → InvalidRequestException")
    void createRateMismatchWithOffer() throws Exception {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = service(repository, calculatorService, offerService);

        UUID userId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        JsonNode params = objectMapper.readTree("{\"price\":1000000,\"downPayment\":100000,\"termMonths\":12,\"annualRate\":5,\"type\":\"ANNUITY\",\"earlyRepayments\":[]}");
        CreateScenarioRequest request = new CreateScenarioRequest("Test", params, offerId);

        BankOffer offer = mock(BankOffer.class);
        when(offer.getRate()).thenReturn(new BigDecimal("12.5"));
        when(offerService.findActiveEntity(offerId)).thenReturn(offer);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not match offer rate");
    }

    @Test
    @DisplayName("create: взнос ниже minDownPaymentPercent оффера → InvalidRequestException")
    void createDownPaymentBelowOfferMinimum() throws Exception {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = service(repository, calculatorService, offerService);

        UUID userId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        JsonNode params = objectMapper.readTree("{\"price\":1000000,\"downPayment\":50000,\"termMonths\":12,\"annualRate\":12,\"type\":\"ANNUITY\",\"earlyRepayments\":[]}");
        CreateScenarioRequest request = new CreateScenarioRequest("Test", params, offerId);

        BankOffer offer = mock(BankOffer.class);
        when(offer.getRate()).thenReturn(new BigDecimal("12"));
        when(offer.getMinDownPaymentPercent()).thenReturn(new BigDecimal("20"));
        when(offerService.findActiveEntity(offerId)).thenReturn(offer);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("below offer minimum");
    }

    @Test
    @DisplayName("create: срок больше maxTermMonths оффера → InvalidRequestException")
    void createTermAboveOfferMaximum() throws Exception {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = service(repository, calculatorService, offerService);

        UUID userId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        JsonNode params = objectMapper.readTree("{\"price\":1000000,\"downPayment\":100000,\"termMonths\":360,\"annualRate\":12,\"type\":\"ANNUITY\",\"earlyRepayments\":[]}");
        CreateScenarioRequest request = new CreateScenarioRequest("Test", params, offerId);

        BankOffer offer = mock(BankOffer.class);
        when(offer.getRate()).thenReturn(new BigDecimal("12"));
        when(offer.getMaxTermMonths()).thenReturn(180);
        when(offerService.findActiveEntity(offerId)).thenReturn(offer);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("exceeds offer maximum");
    }

    @Test
    @DisplayName("create: параметры не проходят bean-validation → InvalidRequestException")
    void createInvalidParams() throws Exception {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = service(repository, calculatorService, offerService);

        UUID userId = UUID.randomUUID();
        JsonNode params = objectMapper.readTree("{\"price\":0,\"downPayment\":100000,\"termMonths\":12,\"annualRate\":12,\"type\":\"ANNUITY\",\"earlyRepayments\":[]}");
        CreateScenarioRequest request = new CreateScenarioRequest("Test", params, null);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("price");
    }

    @Test
    @DisplayName("compare: неверное количество id → InvalidRequestException")
    void compareInvalidCount() {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = service(repository, calculatorService, offerService);

        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.compare(userId, List.of(UUID.randomUUID())))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("delete: чужой сценарий → ScenarioNotFoundException")
    void deleteForeignScenario() {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = service(repository, calculatorService, offerService);

        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        Scenario scenario = new Scenario(owner, "name", "{}", "{}", "calc-1", null);
        // set id via reflection not needed; repository returns scenario
        when(repository.findById(scenarioId)).thenReturn(Optional.of(scenario));

        assertThatThrownBy(() -> service.delete(other, scenarioId))
                .isInstanceOf(ScenarioNotFoundException.class);
    }
}