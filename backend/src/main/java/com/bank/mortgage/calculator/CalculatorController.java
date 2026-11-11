package com.bank.mortgage.calculator;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/calculator")
public class CalculatorController {

    private final CalculatorService calculatorService;
    private final EarlyRepaymentService earlyRepaymentService;
    private final FloatingRateService floatingRateService;

    public CalculatorController(CalculatorService calculatorService,
                                 EarlyRepaymentService earlyRepaymentService,
                                 FloatingRateService floatingRateService) {
        this.calculatorService = calculatorService;
        this.earlyRepaymentService = earlyRepaymentService;
        this.floatingRateService = floatingRateService;
    }

    @PostMapping(value = "/calculate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CalculationResponse> calculate(@RequestBody CalculationRequest request) {
        return calculatorService.calculate(request);
    }

    @PostMapping(value = "/early-repayment", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CalculationResponse> earlyRepayment(@RequestBody EarlyRepaymentRequest request) {
        return earlyRepaymentService.recalculateWithEarlyRepayments(request.scenario(), request.earlyRepayments());
    }

    @PostMapping(value = "/floating-rate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CalculationResponse> floatingRate(@RequestBody FloatingRateRequest request) {
        return floatingRateService.calculateWithRateIncrease(request.baseRequest(), request.increasePercent());
    }

    public record EarlyRepaymentRequest(
            CalculationRequest scenario,
            java.util.List<EarlyRepayment> earlyRepayments
    ) {}

    public record FloatingRateRequest(
            CalculationRequest baseRequest,
            int increasePercent
    ) {}
}