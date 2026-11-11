package dev.nosurprise.mortgage.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import dev.nosurprise.mortgage.calculator.CalculationRequest;
import dev.nosurprise.mortgage.calculator.CalculationResponse;
import dev.nosurprise.mortgage.calculator.EarlyRepayment;
import dev.nosurprise.mortgage.calculator.EarlyRepaymentMode;
import dev.nosurprise.mortgage.calculator.PaymentType;
import dev.nosurprise.mortgage.calculator.ScheduleEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScenarioServiceTest {

    private ScenarioService scenarioService;
    private ScenarioRepository scenarioRepository;
    private ScheduleRepository scheduleRepository;

    @BeforeEach
    void setUp() {
        scenarioRepository = mock(ScenarioRepository.class);
        scheduleRepository = mock(ScheduleRepository.class);
        scenarioService = new ScenarioServiceImpl(scenarioRepository, scheduleRepository);
    }

    @Nested
    @DisplayName("Сохранение и получение сценариев (К16)")
    class SaveAndGetTests {

        @Test
        @DisplayName("saveScenario_withValidData_savesAndReturnsScenario")
        void saveScenario_withValidData_savesAndReturnsScenario() {
            // given
            Long userId = 1L;
            String name = "Мой сценарий";
            CalculationRequest request = new CalculationRequest(
                    new BigDecimal("5000000"),
                    new BigDecimal("1000000"),
                    120,
                    new BigDecimal("0.10"),
                    PaymentType.ANNUITY,
                    List.of()
            );
            CalculationResponse response = new CalculationResponse(
                    new BigDecimal("52333.23"),
                    new BigDecimal("2280000.00"),
                    new BigDecimal("7280000.00"),
                    List.of(new ScheduleEntry(1, new BigDecimal("52333.23"), new BigDecimal("10000.00"), new BigDecimal("42333.23"), new BigDecimal("3990000.00")))
            );

            when(scheduleRepository.findByScheduleHash(anyString())).thenReturn(Mono.empty());
            when(scheduleRepository.save(any(ScheduleDocument.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(scenarioRepository.save(any(Scenario.class))).thenAnswer(inv -> {
                Scenario s = inv.getArgument(0);
                return Mono.just(new Scenario(
                        UUID.randomUUID().toString(), s.userId(), s.name(),
                        s.propertyPrice(), s.downPayment(), s.termMonths(), s.rate(),
                        s.paymentType(), s.earlyRepayments(), s.monthlyPayment(),
                        s.overpayment(), s.totalPayment(), s.scheduleHash(),
                        Instant.now(), Instant.now()
                ));
            });

            // when
            Mono<Scenario> result = scenarioService.saveScenario(userId, name, request, response);

            // then
            StepVerifier.create(result)
                    .assertNext(scenario -> {
                        assertThat(scenario.userId()).isEqualTo(userId);
                        assertThat(scenario.name()).isEqualTo(name);
                        assertThat(scenario.propertyPrice()).isEqualByComparingTo("5000000");
                        assertThat(scenario.monthlyPayment()).isEqualByComparingTo("52333.23");
                    })
                    .verifyComplete();

            verify(scheduleRepository).save(any(ScheduleDocument.class));
            verify(scenarioRepository).save(any(Scenario.class));
        }

        @Test
        @DisplayName("getUserScenarios_returnsUserScenariosSortedByDate")
        void getUserScenarios_returnsUserScenariosSortedByDate() {
            // given
            Long userId = 1L;
            Scenario s1 = createScenario("1", userId, "Старый", Instant.now().minusSeconds(3600));
            Scenario s2 = createScenario("2", userId, "Новый", Instant.now());
            when(scenarioRepository.findByUserId(userId)).thenReturn(Flux.just(s1, s2));

            // when
            Flux<Scenario> result = scenarioService.getUserScenarios(userId);

            // then
            StepVerifier.create(result)
                    .assertNext(s -> assertThat(s.name()).isEqualTo("Новый")) // newest first
                    .assertNext(s -> assertThat(s.name()).isEqualTo("Старый"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("getScenario_withExistingId_returnsScenario")
        void getScenario_withExistingId_returnsScenario() {
            // given
            Long userId = 1L;
            String scenarioId = "scenario-1";
            Scenario scenario = createScenario(scenarioId, userId, "Тест", Instant.now());
            when(scenarioRepository.findByIdAndUserId(scenarioId, userId)).thenReturn(Mono.just(scenario));

            // when
            Mono<Scenario> result = scenarioService.getScenario(userId, scenarioId);

            // then
            StepVerifier.create(result)
                    .assertNext(s -> assertThat(s.id()).isEqualTo(scenarioId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("getScenario_withNonExistentId_throwsException")
        void getScenario_withNonExistentId_throwsException() {
            // given
            when(scenarioRepository.findByIdAndUserId("unknown", 1L)).thenReturn(Mono.empty());

            // when/then
            StepVerifier.create(scenarioService.getScenario(1L, "unknown"))
                    .expectErrorMatches(throwable ->
                            throwable instanceof ScenarioServiceImpl.ScenarioNotFoundException)
                    .verify();
        }

        @Test
        @DisplayName("deleteScenario_withValidId_deletesScenario")
        void deleteScenario_withValidId_deletesScenario() {
            // given
            String scenarioId = "scenario-1";
            Scenario scenario = createScenario(scenarioId, 1L, "Тест", Instant.now());
            when(scenarioRepository.findByIdAndUserId(scenarioId, 1L)).thenReturn(Mono.just(scenario));
            when(scenarioRepository.delete(scenario)).thenReturn(Mono.empty());

            // when
            Mono<Void> result = scenarioService.deleteScenario(1L, scenarioId);

            // then
            StepVerifier.create(result).verifyComplete();
            verify(scenarioRepository).delete(scenario);
        }

        @Test
        @DisplayName("deleteScenario_withNonExistentId_throwsException")
        void deleteScenario_withNonExistentId_throwsException() {
            // given
            when(scenarioRepository.findByIdAndUserId("unknown", 1L)).thenReturn(Mono.empty());

            // when/then
            StepVerifier.create(scenarioService.deleteScenario(1L, "unknown"))
                    .expectErrorMatches(throwable ->
                            throwable instanceof ScenarioServiceImpl.ScenarioNotFoundException)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Сравнение сценариев (К17)")
    class CompareTests {

        @Test
        @DisplayName("compareScenarios_withTwoScenarios_returnsComparison")
        void compareScenarios_withTwoScenarios_returnsComparison() {
            // given
            Long userId = 1L;
            List<String> scenarioIds = List.of("id1", "id2");
            Scenario s1 = createScenario("id1", userId, "Сценарий 1", Instant.now());
            Scenario s2 = createScenario("id2", userId, "Сценарий 2", Instant.now());
            s1 = new Scenario(s1.id(), s1.userId(), s1.name(), s1.propertyPrice(), s1.downPayment(),
                    s1.termMonths(), new BigDecimal("0.10"), s1.paymentType(), s1.earlyRepayments(),
                    new BigDecimal("50000"), new BigDecimal("1000000"), new BigDecimal("4000000"), s1.scheduleHash(), s1.createdAt(), s1.updatedAt());
            s2 = new Scenario(s2.id(), s2.userId(), s2.name(), s2.propertyPrice(), s2.downPayment(),
                    s2.termMonths(), new BigDecimal("0.12"), s2.paymentType(), s2.earlyRepayments(),
                    new BigDecimal("55000"), new BigDecimal("1200000"), new BigDecimal("4200000"), s2.scheduleHash(), s2.createdAt(), s2.updatedAt());

            when(scenarioRepository.findByUserIdAndIds(userId, scenarioIds)).thenReturn(Flux.just(s1, s2));

            // when
            Mono<ComparisonResult> result = scenarioService.compareScenarios(userId, scenarioIds);

            // then
            StepVerifier.create(result)
                    .assertNext(comparison -> {
                        assertThat(comparison.scenarios()).hasSize(2);
                        assertThat(comparison.monthlyPaymentComparison()).containsEntry("Сценарий 1", new BigDecimal("50000"));
                        assertThat(comparison.monthlyPaymentComparison()).containsEntry("Сценарий 2", new BigDecimal("55000"));
                        assertThat(comparison.overpaymentComparison()).containsEntry("Сценарий 1", new BigDecimal("1000000"));
                        assertThat(comparison.overpaymentComparison()).containsEntry("Сценарий 2", new BigDecimal("1200000"));
                        assertThat(comparison.totalPaymentComparison()).containsEntry("Сценарий 1", new BigDecimal("4000000"));
                        assertThat(comparison.totalPaymentComparison()).containsEntry("Сценарий 2", new BigDecimal("4200000"));
                        assertThat(comparison.termComparison()).containsEntry("Сценарий 1", 120);
                        assertThat(comparison.termComparison()).containsEntry("Сценарий 2", 120);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("compareScenarios_withThreeScenarios_returnsComparison")
        void compareScenarios_withThreeScenarios_returnsComparison() {
            // given
            Long userId = 1L;
            List<String> scenarioIds = List.of("id1", "id2", "id3");
            Scenario s1 = createScenario("id1", userId, "Сценарий 1", Instant.now());
            Scenario s2 = createScenario("id2", userId, "Сценарий 2", Instant.now());
            Scenario s3 = createScenario("id3", userId, "Сценарий 3", Instant.now());

            when(scenarioRepository.findByUserIdAndIds(userId, scenarioIds)).thenReturn(Flux.just(s1, s2, s3));

            // when
            Mono<ComparisonResult> result = scenarioService.compareScenarios(userId, scenarioIds);

            // then
            StepVerifier.create(result)
                    .assertNext(comparison -> {
                        assertThat(comparison.scenarios()).hasSize(3);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("compareScenarios_withOneScenario_throwsException")
        void compareScenarios_withOneScenario_throwsException() {
            // given
            List<String> scenarioIds = List.of("id1");

            // when/then
            StepVerifier.create(scenarioService.compareScenarios(1L, scenarioIds))
                    .expectErrorMatches(throwable ->
                            throwable instanceof IllegalArgumentException &&
                                    throwable.getMessage().contains("2 or 3"))
                    .verify();
        }

        @Test
        @DisplayName("compareScenarios_withFourScenarios_throwsException")
        void compareScenarios_withFourScenarios_throwsException() {
            // given
            List<String> scenarioIds = List.of("id1", "id2", "id3", "id4");

            // when/then
            StepVerifier.create(scenarioService.compareScenarios(1L, scenarioIds))
                    .expectErrorMatches(throwable ->
                            throwable instanceof IllegalArgumentException &&
                                    throwable.getMessage().contains("2 or 3"))
                    .verify();
        }

        @Test
        @DisplayName("compareScenarios_withNonExistentScenario_throwsException")
        void compareScenarios_withNonExistentScenario_throwsException() {
            // given
            Long userId = 1L;
            List<String> scenarioIds = List.of("id1", "id2");
            Scenario s1 = createScenario("id1", userId, "Сценарий 1", Instant.now());
            when(scenarioRepository.findByUserIdAndIds(userId, scenarioIds)).thenReturn(Flux.just(s1));

            // when/then
            StepVerifier.create(scenarioService.compareScenarios(userId, scenarioIds))
                    .expectErrorMatches(throwable ->
                            throwable instanceof ScenarioServiceImpl.ScenarioNotFoundException)
                    .verify();
        }

        @Test
        @DisplayName("compareScenarios_preservesOrderOfInputIds")
        void compareScenarios_preservesOrderOfInputIds() {
            // given
            Long userId = 1L;
            List<String> scenarioIds = List.of("id2", "id1"); // reversed order
            Scenario s1 = createScenario("id1", userId, "Сценарий 1", Instant.now());
            Scenario s2 = createScenario("id2", userId, "Сценарий 2", Instant.now());

            when(scenarioRepository.findByUserIdAndIds(userId, scenarioIds)).thenReturn(Flux.just(s1, s2));

            // when
            Mono<ComparisonResult> result = scenarioService.compareScenarios(userId, scenarioIds);

            // then
            StepVerifier.create(result)
                    .assertNext(comparison -> {
                        assertThat(comparison.scenarios().get(0).name()).isEqualTo("Сценарий 2");
                        assertThat(comparison.scenarios().get(1).name()).isEqualTo("Сценарий 1");
                    })
                    .verifyComplete();
        }
    }

    private Scenario createScenario(String id, Long userId, String name, Instant createdAt) {
        return new Scenario(
                id, userId, name,
                new BigDecimal("5000000"), new BigDecimal("1000000"), 120,
                new BigDecimal("0.10"), "ANNUITY", List.of(),
                new BigDecimal("50000"), new BigDecimal("1000000"), new BigDecimal("6000000"),
                "hash-" + id, createdAt, Instant.now()
        );
    }
}







