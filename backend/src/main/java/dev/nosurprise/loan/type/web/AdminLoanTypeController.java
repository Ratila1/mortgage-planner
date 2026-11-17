package dev.nosurprise.loan.type.web;

import dev.nosurprise.loan.type.InvalidLoanTypeException;
import dev.nosurprise.loan.type.LoanTypeService;
import dev.nosurprise.loan.type.web.dto.LoanTypeRequest;
import dev.nosurprise.loan.type.web.dto.LoanTypeResponse;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Типы займов в CRM админа (MICROLOANS.md §3.1). Удаления нет — только выключение. */
@RestController
@RequestMapping("/api/admin/loan-types")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class AdminLoanTypeController {

    private final LoanTypeService service;

    public AdminLoanTypeController(LoanTypeService service) {
        this.service = service;
    }

    @GetMapping
    List<LoanTypeResponse> list() {
        return service.findAll().stream().map(LoanTypeResponse::from).toList();
    }

    @PostMapping
    ResponseEntity<LoanTypeResponse> create(@Valid @RequestBody LoanTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(LoanTypeResponse.from(service.create(request.toData())));
    }

    @PutMapping("/{id}")
    LoanTypeResponse update(@PathVariable UUID id, @Valid @RequestBody LoanTypeRequest request) {
        if (request.version() == null) {
            throw new InvalidLoanTypeException("version is required");
        }
        return LoanTypeResponse.from(service.update(id, request.toData(), request.version()));
    }

    @PostMapping("/{id}/deactivate")
    LoanTypeResponse deactivate(@PathVariable UUID id) {
        return LoanTypeResponse.from(service.deactivate(id));
    }
}
