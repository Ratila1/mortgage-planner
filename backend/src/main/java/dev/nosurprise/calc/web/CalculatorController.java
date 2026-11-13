package dev.nosurprise.calc.web;

import dev.nosurprise.calc.service.CalculatorService;
import dev.nosurprise.calc.service.CalcNotFoundException;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.CalcResponse;
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

import java.util.UUID;

/**
 * HTTP API расчёта (§9): POST /api/calc, GET /api/calc/{calcId}. Публичные пути (§7).
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
        UUID userId = null;
        if (jwt != null) {
            try {
                userId = UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException ex) {
                throw new CalcNotFoundException("calculation not found");
            }
        }
        return service.calculate(request, userId);
    }

    @GetMapping("/{calcId}")
    CalcResponse get(@PathVariable String calcId) {
        return service.get(calcId);
    }
}
