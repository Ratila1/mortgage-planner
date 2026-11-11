package dev.nosurprise.mortgage.offer;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OfferRepository extends ReactiveCrudRepository<Offer, Long> {

    @Query("SELECT * FROM offers WHERE is_active = TRUE ORDER BY rate ASC")
    Flux<Offer> findActiveOffers();

    @Query("SELECT * FROM offers WHERE id = :id")
    Mono<Offer> findById(Long id);
}







