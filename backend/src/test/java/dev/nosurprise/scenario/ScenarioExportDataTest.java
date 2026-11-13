package dev.nosurprise.scenario;

import dev.nosurprise.calc.service.CalculationReadModel;
import dev.nosurprise.calc.service.CalculatorService;
import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.LoanSummary;
import dev.nosurprise.offer.OfferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioExportDataTest {

    @Test
    @DisplayName("владелец получает данные экспорта")
    void ownerGetsData() {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = new ScenarioService(repository, calculatorService, offerService,
                new tools.jackson.databind.ObjectMapper());

        UUID userId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        Scenario scenario = new Scenario(userId, "Scenario", "{}", "{}", "507f1f77bcf86cd799439011", null);
        setField(scenario, "id", scenarioId);

        when(repository.findById(scenarioId)).thenReturn(java.util.Optional.of(scenario));
        CalculationReadModel calc = new CalculationReadModel("507f1f77bcf86cd799439011",
                new LoanParams(new BigDecimal("1000000"), new BigDecimal("100000"), 12, new BigDecimal("12"),
                        dev.nosurprise.calculator.PaymentType.ANNUITY, List.of()),
                new LoanSummary(new BigDecimal("80000.00"), new BigDecimal("10000.00"), new BigDecimal("960000.00"), 12),
                List.of(), Instant.now(), null);
        when(calculatorService.getCalculation("507f1f77bcf86cd799439011")).thenReturn(calc);

        ScenarioExportData data = service.getScenarioExportData(userId, scenarioId);

        assertThat(data.scenarioId()).isEqualTo(scenarioId);
        assertThat(data.userId()).isEqualTo(userId);
        assertThat(data.calcId()).isEqualTo("507f1f77bcf86cd799439011");
    }

    @Test
    @DisplayName("чужой сценарий -> SCENARIO_NOT_FOUND")
    void foreignScenario() {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = new ScenarioService(repository, calculatorService, offerService,
                new tools.jackson.databind.ObjectMapper());

        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        Scenario scenario = new Scenario(ownerId, "Scenario", "{}", "{}", "507f1f77bcf86cd799439011", null);
        setField(scenario, "id", scenarioId);
        when(repository.findById(scenarioId)).thenReturn(java.util.Optional.of(scenario));

        assertThatThrownBy(() -> service.getScenarioExportData(otherId, scenarioId))
                .isInstanceOf(dev.nosurprise.scenario.ScenarioNotFoundException.class);
    }

    @Test
    @DisplayName("отсутствующий сценарий -> SCENARIO_NOT_FOUND")
    void missingScenario() {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        CalculatorService calculatorService = mock(CalculatorService.class);
        OfferService offerService = mock(OfferService.class);
        ScenarioService service = new ScenarioService(repository, calculatorService, offerService,
                new tools.jackson.databind.ObjectMapper());

        UUID userId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        when(repository.findById(scenarioId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getScenarioExportData(userId, scenarioId))
                .isInstanceOf(dev.nosurprise.scenario.ScenarioNotFoundException.class);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}