package dev.nosurprise.mortgage.offer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OfferServiceTest {

    @Mock
    private OfferRepository offerRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private TransactionalOperator transactionalOperator;

    @Captor
    ArgumentCaptor<String> exchangeCaptor;
    @Captor
    ArgumentCaptor<String> routingKeyCaptor;
    @Captor
    ArgumentCaptor<Object> eventCaptor;

    private OfferService offerService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        offerService = new OfferServiceImpl(
                offerRepository,
                rabbitTemplate,
                "rate.changed.exchange",
                "rate.changed.key",
                transactionalOperator
        );
    }

    @Nested
    @DisplayName("CRUD предложений (К14)")
    class CrudTests {

        @Test
        @DisplayName("createOffer_withValidData_returnsOffer")
        void createOffer_withValidData_returnsOffer() {
            // given
            OfferRequest request = new OfferRequest("Сбербанк", new BigDecimal("0.095"), new BigDecimal("15"), 360);
            Offer savedOffer = Offer.builder()
                    .id(1L)
                    .bankName("Сбербанк")
                    .rate(new BigDecimal("0.095"))
                    .minDownPaymentPercent(new BigDecimal("15"))
                    .maxTermMonths(360)
                    .isActive(true)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            when(offerRepository.save(any(Offer.class))).thenReturn(Mono.just(savedOffer));

            // when
            Mono<Offer> result = offerService.createOffer(request);

            // then
            StepVerifier.create(result)
                    .assertNext(offer -> {
                        assertThat(offer.bankName()).isEqualTo("Сбербанк");
                        assertThat(offer.rate()).isEqualByComparingTo("0.095");
                        assertThat(offer.minDownPaymentPercent()).isEqualByComparingTo("15");
                        assertThat(offer.maxTermMonths()).isEqualTo(360);
                        assertThat(offer.isActive()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("getAllOffers_returnsAllOffers")
        void getAllOffers_returnsAllOffers() {
            // given
            Offer offer1 = Offer.builder().id(1L).bankName("Банк 1").rate(new BigDecimal("0.10")).minDownPaymentPercent(new BigDecimal("10")).maxTermMonths(240).isActive(true).build();
            Offer offer2 = Offer.builder().id(2L).bankName("Банк 2").rate(new BigDecimal("0.09")).minDownPaymentPercent(new BigDecimal("15")).maxTermMonths(300).isActive(true).build();
            when(offerRepository.findAll()).thenReturn(Flux.just(offer1, offer2));

            // when
            Flux<Offer> result = offerService.getAllOffers();

            // then
            StepVerifier.create(result)
                    .expectNextCount(2)
                    .verifyComplete();
        }

        @Test
        @DisplayName("getActiveOffers_returnsOnlyActiveOffers")
        void getActiveOffers_returnsOnlyActiveOffers() {
            // given
            Offer active = Offer.builder().id(1L).bankName("Активный").rate(new BigDecimal("0.10")).minDownPaymentPercent(new BigDecimal("10")).maxTermMonths(240).isActive(true).build();
            when(offerRepository.findActiveOffers()).thenReturn(Flux.just(active));

            // when
            Flux<Offer> result = offerService.getActiveOffers();

            // then
            StepVerifier.create(result)
                    .assertNext(offer -> assertThat(offer.isActive()).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("getOffer_withExistingId_returnsOffer")
        void getOffer_withExistingId_returnsOffer() {
            // given
            Offer offer = Offer.builder().id(1L).bankName("Тест").rate(new BigDecimal("0.10")).minDownPaymentPercent(new BigDecimal("10")).maxTermMonths(240).isActive(true).build();
            when(offerRepository.findById(1L)).thenReturn(Mono.just(offer));

            // when
            Mono<Offer> result = offerService.getOffer(1L);

            // then
            StepVerifier.create(result)
                    .assertNext(o -> assertThat(o.id()).isEqualTo(1L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("getOffer_withNonExistentId_throwsException")
        void getOffer_withNonExistentId_throwsException() {
            // given
            when(offerRepository.findById(999L)).thenReturn(Mono.empty());

            // when/then
            StepVerifier.create(offerService.getOffer(999L))
                    .expectErrorMatches(throwable ->
                            throwable instanceof OfferServiceImpl.OfferNotFoundException)
                    .verify();
        }

        @Test
        @DisplayName("updateOffer_withValidData_updatesOffer")
        void updateOffer_withValidData_updatesOffer() {
            // given
            Offer existing = Offer.builder()
                    .id(1L)
                    .bankName("Старый банк")
                    .rate(new BigDecimal("0.10"))
                    .minDownPaymentPercent(new BigDecimal("10"))
                    .maxTermMonths(240)
                    .isActive(true)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            OfferRequest request = new OfferRequest("Новый банк", new BigDecimal("0.09"), new BigDecimal("15"), 300);
            when(offerRepository.findById(1L)).thenReturn(Mono.just(existing));
            when(offerRepository.save(any(Offer.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            // when
            Mono<Offer> result = offerService.updateOffer(1L, request);

            // then
            StepVerifier.create(result)
                    .assertNext(offer -> {
                        assertThat(offer.bankName()).isEqualTo("Новый банк");
                        assertThat(offer.rate()).isEqualByComparingTo("0.09");
                        assertThat(offer.minDownPaymentPercent()).isEqualByComparingTo("15");
                        assertThat(offer.maxTermMonths()).isEqualTo(300);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("deleteOffer_withExistingId_deletesOffer")
        void deleteOffer_withExistingId_deletesOffer() {
            // given
            Offer offer = Offer.builder().id(1L).bankName("Тест").rate(new BigDecimal("0.10")).minDownPaymentPercent(new BigDecimal("10")).maxTermMonths(240).isActive(true).build();
            when(offerRepository.findById(1L)).thenReturn(Mono.just(offer));
            when(offerRepository.delete(offer)).thenReturn(Mono.empty());

            // when
            Mono<Void> result = offerService.deleteOffer(1L);

            // then
            StepVerifier.create(result).verifyComplete();
            verify(offerRepository).delete(offer);
        }

        @Test
        @DisplayName("deleteOffer_withNonExistentId_throwsException")
        void deleteOffer_withNonExistentId_throwsException() {
            // given
            when(offerRepository.findById(999L)).thenReturn(Mono.empty());

            // when/then
            StepVerifier.create(offerService.deleteOffer(999L))
                    .expectErrorMatches(throwable ->
                            throwable instanceof OfferServiceImpl.OfferNotFoundException)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Событие RateChanged (К15)")
    class RateChangedEventTests {

        @Test
        @DisplayName("updateOffer_withRateChange_publishesRateChangedEvent")
        void updateOffer_withRateChange_publishesRateChangedEvent() {
            // given
            Offer existing = Offer.builder()
                    .id(1L)
                    .bankName("Сбербанк")
                    .rate(new BigDecimal("0.095"))
                    .minDownPaymentPercent(new BigDecimal("15"))
                    .maxTermMonths(360)
                    .isActive(true)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            OfferRequest request = new OfferRequest("Сбербанк", new BigDecimal("0.105"), new BigDecimal("15"), 360); // rate changed from 9.5% to 10.5%
            when(offerRepository.findById(1L)).thenReturn(Mono.just(existing));
            when(offerRepository.save(any(Offer.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            doNothing().when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(RateChangedEvent.class));

            // when
            Mono<Offer> result = offerService.updateOffer(1L, request);

            // then
            StepVerifier.create(result)
                    .assertNext(offer -> {
                        assertThat(offer.rate()).isEqualByComparingTo("0.105");
                    })
                    .verifyComplete();

            // Verify event was published using ArgumentCaptor
            verify(rabbitTemplate).convertAndSend(exchangeCaptor.capture(), routingKeyCaptor.capture(), eventCaptor.capture());
            assertThat(exchangeCaptor.getValue()).isEqualTo("rate.changed.exchange");
            assertThat(routingKeyCaptor.getValue()).isEqualTo("rate.changed.key");
            assertThat(eventCaptor.getValue()).isInstanceOf(RateChangedEvent.class);
            RateChangedEvent event = (RateChangedEvent) eventCaptor.getValue();
            assertThat(event.offerId()).isEqualTo(1L);
            assertThat(event.oldRate()).isEqualByComparingTo("0.095");
            assertThat(event.newRate()).isEqualByComparingTo("0.105");
        }

        @Test
        @DisplayName("updateOffer_withoutRateChange_doesNotPublishEvent")
        void updateOffer_withoutRateChange_doesNotPublishEvent() {
            // given
            Offer existing = Offer.builder()
                    .id(1L)
                    .bankName("Сбербанк")
                    .rate(new BigDecimal("0.095"))
                    .minDownPaymentPercent(new BigDecimal("15"))
                    .maxTermMonths(360)
                    .isActive(true)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            OfferRequest request = new OfferRequest("Сбербанк", new BigDecimal("0.095"), new BigDecimal("20"), 360); // rate unchanged, only minDownPayment changed
            when(offerRepository.findById(1L)).thenReturn(Mono.just(existing));
            when(offerRepository.save(any(Offer.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            lenient().doNothing().when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(RateChangedEvent.class));

            // when
            Mono<Offer> result = offerService.updateOffer(1L, request);

            // then
            StepVerifier.create(result)
                    .expectNextCount(1)
                    .verifyComplete();

            // Verify event was NOT published
            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(RateChangedEvent.class));
        }

        @Test
        @DisplayName("updateOffer_rateChangedToZero_publishesEvent")
        void updateOffer_rateChangedToZero_publishesEvent() {
            // given
            Offer existing = Offer.builder()
                    .id(1L)
                    .bankName("Банк")
                    .rate(new BigDecimal("0.10"))
                    .minDownPaymentPercent(new BigDecimal("10"))
                    .maxTermMonths(240)
                    .isActive(true)
                    .build();
            OfferRequest request = new OfferRequest("Банк", BigDecimal.ZERO, new BigDecimal("10"), 240);
            when(offerRepository.findById(1L)).thenReturn(Mono.just(existing));
            when(offerRepository.save(any(Offer.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            doNothing().when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(RateChangedEvent.class));

            // when
            Mono<Offer> result = offerService.updateOffer(1L, request);

            // then
            StepVerifier.create(result)
                    .expectNextCount(1)
                    .verifyComplete();
            verify(rabbitTemplate).convertAndSend(exchangeCaptor.capture(), routingKeyCaptor.capture(), eventCaptor.capture());
            assertThat(eventCaptor.getValue()).isInstanceOf(RateChangedEvent.class);
        }
    }
}







