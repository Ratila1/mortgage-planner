package dev.nosurprise.mortgage.user;

import dev.nosurprise.mortgage.auth.User;
import dev.nosurprise.mortgage.calculator.CalculationRequest;
import dev.nosurprise.mortgage.calculator.CalculationResponse;
import dev.nosurprise.mortgage.calculator.CalculatorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final CalculatorService calculatorService;
    private final ScheduleRepository scheduleRepository;

    public ScenarioController(ScenarioService scenarioService, CalculatorService calculatorService,
                               ScheduleRepository scheduleRepository) {
        this.scenarioService = scenarioService;
        this.calculatorService = calculatorService;
        this.scheduleRepository = scheduleRepository;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Scenario> saveScenario(@AuthenticationPrincipal User user,
                                        @Valid @RequestBody SaveScenarioRequest request) {
        return calculatorService.calculate(request.calculationRequest())
                .flatMap(response -> scenarioService.saveScenario(user.id(), request.name(), request.calculationRequest(), response));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Scenario> getScenarios(@AuthenticationPrincipal User user) {
        return scenarioService.getUserScenarios(user.id());
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ScenarioWithSchedule> getScenario(@AuthenticationPrincipal User user, @PathVariable String id) {
        return scenarioService.getScenario(user.id(), id)
                .flatMap(scenario -> scheduleRepository.findByScheduleHash(scenario.scheduleHash())
                        .map(scheduleDoc -> new ScenarioWithSchedule(scenario, scheduleDoc.entries()))
                        .switchIfEmpty(Mono.just(new ScenarioWithSchedule(scenario, List.of()))));
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteScenario(@AuthenticationPrincipal User user, @PathVariable String id) {
        return scenarioService.deleteScenario(user.id(), id);
    }

    @GetMapping(value = "/compare", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ComparisonResult> compareScenarios(@AuthenticationPrincipal User user,
                                                    @RequestParam List<String> id) {
        return scenarioService.compareScenarios(user.id(), id);
    }

    public record SaveScenarioRequest(
            @NotBlank @Size(min = 1, max = 255) String name,
            CalculationRequest calculationRequest
    ) {}

    public record ScenarioWithSchedule(
            Scenario scenario,
            List<dev.nosurprise.mortgage.calculator.ScheduleEntry> schedule
    ) {}
}







