package dev.nosurprise.offer;

import dev.nosurprise.offer.event.OfferRateChanged;
import dev.nosurprise.offer.web.dto.CreateOfferRequest;
import dev.nosurprise.offer.web.dto.OfferResponse;
import dev.nosurprise.offer.web.dto.UpdateOfferRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OfferService {

    private final BankOfferRepository repository;
    private final OfferCache cache;
    private final ApplicationEventPublisher eventPublisher;

    public OfferService(BankOfferRepository repository, OfferCache cache,
                        ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.cache = cache;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<OfferResponse> findAllActive() {
        return cache.get().orElseGet(() -> {
            List<OfferResponse> offers = repository.findAllByActiveTrue().stream()
                    .map(OfferResponse::from)
                    .toList();
            cache.put(offers);
            return offers;
        });
    }

    @Transactional(readOnly = true)
    public BankOffer findEntity(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new OfferNotFoundException("offer not found: " + id));
    }

    @Transactional(readOnly = true)
    public BankOffer findActiveEntity(UUID id) {
        BankOffer offer = findEntity(id);
        if (!offer.isActive()) {
            throw new OfferNotFoundException("offer not found: " + id);
        }
        return offer;
    }

    @Transactional
    public OfferResponse create(CreateOfferRequest request) {
        boolean active = request.active() == null || request.active();
        BankOffer offer = new BankOffer(
                request.bankName(),
                request.rate(),
                request.minDownPaymentPercent(),
                request.maxTermMonths(),
                active);
        BankOffer saved = repository.save(offer);
        cache.evict();
        return OfferResponse.from(saved);
    }

    @Transactional
    public OfferResponse update(UUID id, UpdateOfferRequest request) {
        BankOffer offer = findEntity(id);
        BigDecimal oldRate = offer.getRate();
        boolean rateChanged = oldRate.compareTo(request.rate()) != 0;

        offer.setBankName(request.bankName());
        offer.setRate(request.rate());
        offer.setMinDownPaymentPercent(request.minDownPaymentPercent());
        offer.setMaxTermMonths(request.maxTermMonths());
        offer.setActive(request.active());
        offer.touch();
        BankOffer saved = repository.save(offer);
        cache.evict();

        if (rateChanged) {
            eventPublisher.publishEvent(new OfferRateChanged(
                    UUID.randomUUID(), Instant.now(), id, oldRate, request.rate()));
        }
        return OfferResponse.from(saved);
    }

    @Transactional
    public void delete(UUID id) {
        BankOffer offer = findEntity(id);
        repository.delete(offer);
        cache.evict();
    }
}