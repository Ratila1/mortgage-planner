package dev.nosurprise.mortgage.offer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OfferService {
    Flux<Offer> getAllOffers();
    Flux<Offer> getActiveOffers();
    Mono<Offer> getOffer(Long id);
    Mono<Offer> createOffer(OfferRequest request);
    Mono<Offer> updateOffer(Long id, OfferRequest request);
    Mono<Void> deleteOffer(Long id);
}







