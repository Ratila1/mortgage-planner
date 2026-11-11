package dev.nosurprise.mortgage.offer;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class OfferServiceImpl implements OfferService {

    private final OfferRepository offerRepository;
    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;
    private final TransactionalOperator transactionalOperator;

    public OfferServiceImpl(OfferRepository offerRepository,
                            RabbitTemplate rabbitTemplate,
                            @Value("${rabbitmq.exchange.rate-changed}") String exchange,
                            @Value("${rabbitmq.routing-key.rate-changed}") String routingKey,
                            TransactionalOperator transactionalOperator) {
        this.offerRepository = offerRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.transactionalOperator = transactionalOperator;
    }

    @Override
    public Flux<Offer> getAllOffers() {
        return offerRepository.findAll();
    }

    @Override
    public Flux<Offer> getActiveOffers() {
        return offerRepository.findActiveOffers();
    }

    @Override
    public Mono<Offer> getOffer(Long id) {
        return offerRepository.findById(id)
                .switchIfEmpty(Mono.error(new OfferNotFoundException("Offer not found")));
    }

    @Override
    public Mono<Offer> createOffer(OfferRequest request) {
        Offer offer = Offer.builder()
                .bankName(request.bankName())
                .rate(request.rate())
                .minDownPaymentPercent(request.minDownPaymentPercent())
                .maxTermMonths(request.maxTermMonths())
                .isActive(true)
                .build();

        return offerRepository.save(offer);
    }

    @Override
    public Mono<Offer> updateOffer(Long id, OfferRequest request) {
        return offerRepository.findById(id)
                .switchIfEmpty(Mono.error(new OfferNotFoundException("Offer not found")))
                .flatMap(existing -> {
                    BigDecimal oldRate = existing.rate();
                    BigDecimal newRate = request.rate();
                    boolean rateChanged = oldRate.compareTo(newRate) != 0;

                    Offer updated = new Offer(
                            existing.id(),
                            request.bankName(),
                            newRate,
                            request.minDownPaymentPercent(),
                            request.maxTermMonths(),
                            existing.isActive(),
                            existing.createdAt(),
                            Instant.now()
                    );

                    return offerRepository.save(updated)
                            .flatMap(saved -> {
                                if (rateChanged) {
                                    RateChangedEvent event = new RateChangedEvent(
                                            saved.id(), saved.bankName(), oldRate, newRate, Instant.now()
                                    );
                                    // Publish asynchronously, don't block
                                    rabbitTemplate.convertAndSend(exchange, routingKey, event);
                                }
                                return Mono.just(saved);
                            });
                });
    }

    @Override
    public Mono<Void> deleteOffer(Long id) {
        return offerRepository.findById(id)
                .switchIfEmpty(Mono.error(new OfferNotFoundException("Offer not found")))
                .flatMap(offerRepository::delete)
                .then();
    }

    public static class OfferNotFoundException extends RuntimeException {
        public OfferNotFoundException(String message) { super(message); }
    }
}







