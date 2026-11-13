package dev.nosurprise.offer.web;

import dev.nosurprise.offer.OfferService;
import dev.nosurprise.offer.web.dto.OfferResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/offers")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class OfferController {

    private final OfferService offerService;

    public OfferController(OfferService offerService) {
        this.offerService = offerService;
    }

    @GetMapping
    public List<OfferResponse> list() {
        return offerService.findAllActive();
    }
}