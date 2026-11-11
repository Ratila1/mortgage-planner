package dev.nosurprise.calc.web;

import dev.nosurprise.calc.service.CalculatorService;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.CalcResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP API расчёта (§9): POST /api/calc, GET /api/calc/{calcId}. Публичные пути (§7).
 * Контроллер ходит только в service, наружу — DTO, не Mongo-документ.
 */
@RestController
@RequestMapping("/api/calc")
public class CalculatorController {

    private final CalculatorService service;

    public CalculatorController(CalculatorService service) {
        this.service = service;
    }

    @PostMapping
    CalcResponse calculate(@Valid @RequestBody CalcRequest request) {
        return service.calculate(request);
    }

    @GetMapping("/{calcId}")
    CalcResponse get(@PathVariable String calcId) {
        return service.get(calcId);
    }
}
