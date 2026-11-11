package dev.nosurprise.offer;

import dev.nosurprise.offer.web.dto.CreateOfferRequest;
import dev.nosurprise.offer.web.dto.OfferResponse;
import dev.nosurprise.offer.web.dto.UpdateOfferRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfferServiceTest {

    @Test
    @DisplayName("findAllActive: cache hit не обращается к репозиторию")
    void cacheHit() {
        BankOfferRepository repository = mock(BankOfferRepository.class);
        OfferCache cache = mock(OfferCache.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        OfferService service = new OfferService(repository, cache, events);

        OfferResponse cached = new OfferResponse(UUID.randomUUID(), "Bank", "9.50", "10.00", 360, true, java.time.Instant.now());
        when(cache.get()).thenReturn(Optional.of(List.of(cached)));

        assertThat(service.findAllActive()).containsExactly(cached);
        verify(repository, never()).findAllByActiveTrue();
    }

    @Test
    @DisplayName("create: сохраняет, сбрасывает кэш")
    void createEvictsCache() {
        BankOfferRepository repository = mock(BankOfferRepository.class);
        OfferCache cache = mock(OfferCache.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        OfferService service = new OfferService(repository, cache, events);

        BankOffer saved = new BankOffer("Bank", new BigDecimal("9.50"), new BigDecimal("10.00"), 360, true);
        when(repository.save(any())).thenReturn(saved);

        CreateOfferRequest req = new CreateOfferRequest("Bank", new BigDecimal("9.50"), new BigDecimal("10.00"), 360, true);
        OfferResponse response = service.create(req);

        assertThat(response.bankName()).isEqualTo("Bank");
        verify(cache).evict();
    }

    @Test
    @DisplayName("update со сменой rate публикует OfferRateChanged")
    void updateRateChangedPublishesEvent() {
        BankOfferRepository repository = mock(BankOfferRepository.class);
        OfferCache cache = mock(OfferCache.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        OfferService service = new OfferService(repository, cache, events);

        UUID id = UUID.randomUUID();
        BankOffer existing = new BankOffer("Bank", new BigDecimal("9.50"), new BigDecimal("10.00"), 360, true);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateOfferRequest req = new UpdateOfferRequest("Bank", new BigDecimal("10.00"), new BigDecimal("10.00"), 360, true);
        service.update(id, req);

        verify(events).publishEvent(org.mockito.ArgumentMatchers.any(dev.nosurprise.offer.event.OfferRateChanged.class));
        verify(cache).evict();
    }

    @Test
    @DisplayName("update без смены rate не публикует событие")
    void updateRateUnchangedNoEvent() {
        BankOfferRepository repository = mock(BankOfferRepository.class);
        OfferCache cache = mock(OfferCache.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        OfferService service = new OfferService(repository, cache, events);

        UUID id = UUID.randomUUID();
        BankOffer existing = new BankOffer("Bank", new BigDecimal("9.50"), new BigDecimal("10.00"), 360, true);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateOfferRequest req = new UpdateOfferRequest("Bank", new BigDecimal("9.50"), new BigDecimal("10.00"), 360, true);
        service.update(id, req);

        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("delete: удаляет и сбрасывает кэш")
    void deleteEvictsCache() {
        BankOfferRepository repository = mock(BankOfferRepository.class);
        OfferCache cache = mock(OfferCache.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        OfferService service = new OfferService(repository, cache, events);

        UUID id = UUID.randomUUID();
        BankOffer existing = new BankOffer("Bank", new BigDecimal("9.50"), new BigDecimal("10.00"), 360, true);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        service.delete(id);

        verify(repository).delete(existing);
        verify(cache).evict();
    }
}