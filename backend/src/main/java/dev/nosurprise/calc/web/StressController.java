package dev.nosurprise.calc.web;

import dev.nosurprise.calc.service.StressService;
import dev.nosurprise.calc.web.dto.StressRequest;
import dev.nosurprise.calc.web.dto.StressResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/calc")
public class StressController {

    private final StressService service;

    public StressController(StressService service) {
        this.service = service;
    }

    @PostMapping("/stress")
    List<StressResponse> stress(@Valid @RequestBody StressRequest request) {
        return service.stress(request);
    }
}