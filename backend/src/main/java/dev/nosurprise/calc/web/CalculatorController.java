package dev.nosurprise.calc.web;

import dev.nosurprise.calc.service.CalculatorService;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.CalcResponse;
import dev.nosurprise.common.problem.InvalidPrincipalException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * HTTP API расчёта (§9): POST /api/calc, GET /api/calc/{calcId}, GET /api/calc/history.
 * Публичные пути (§7): аноним может считать и смотреть анонимные расчёты;
 * расчёты пользователя видит только владелец (чужой — тот же 404).
 * Контроллер ходит только в service, наружу — DTO, не Mongo-документ.
 */
@RestController
@RequestMapping("/api/calc")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class CalculatorController {

    private final CalculatorService service;

    public CalculatorController(CalculatorService service) {
        this.service = service;
    }

    @PostMapping
    CalcResponse calculate(@Valid @RequestBody CalcRequest request,
                           @AuthenticationPrincipal Jwt jwt) {
        return service.calculate(request, parseViewer(jwt));
    }

    @GetMapping("/{calcId}")
    CalcResponse get(@PathVariable String calcId, @AuthenticationPrincipal Jwt jwt) {
        return service.get(calcId, parseViewer(jwt));
    }

    /** История расчётов пользователя (без графика). Аноним получает пустой список. */
    @GetMapping("/history")
    List<CalcResponse> history(@AuthenticationPrincipal Jwt jwt) {
        return service.history(parseViewer(jwt));
    }

    private static UUID parseViewer(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException ex) {
            throw new InvalidPrincipalException();
        }
    }
}
