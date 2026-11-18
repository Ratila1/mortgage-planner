package dev.nosurprise.loan.type.web;

import dev.nosurprise.loan.type.LoanTypeService;
import dev.nosurprise.loan.type.web.dto.LoanTypeResponse;
import dev.nosurprise.loan.type.web.dto.QuoteRequest;
import dev.nosurprise.loan.type.web.dto.QuoteResponse;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Публичные эндпоинты: активные типы и калькулятор (без сохранения), MICROLOANS.md §4.6. */
@RestController
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class LoanTypeController {

    private final LoanTypeService service;

    public LoanTypeController(LoanTypeService service) {
        this.service = service;
    }

    @GetMapping("/api/loan-types")
    List<LoanTypeResponse> active() {
        return service.findActive().stream().map(LoanTypeResponse::from).toList();
    }

    @PostMapping("/api/loans/quote")
    QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
        return QuoteResponse.from(service.quote(request.loanTypeId(), request.amount(), request.termDays()));
    }
}
