package dev.nosurprise.offer;

import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.offer.event.OfferRateChanged;
import dev.nosurprise.offer.event.OfferRateChangedPublisher;
import dev.nosurprise.offer.web.dto.UpdateOfferRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfferRateChangedAfterCommitTest {

    static class StubTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (status.isRollbackOnly()) {
                rollback(status);
                return;
            }
            TransactionSynchronizationUtils.triggerAfterCommit();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            TransactionSynchronizationManager.clear();
        }

        @Override
        public void rollback(TransactionStatus status) {
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @DisplayName("offer.rate.changed публикуется только после коммита")
    void publishedAfterCommit() {
        BankOfferRepository repository = mock(BankOfferRepository.class);
        OfferCache cache = mock(OfferCache.class);
        UUID id = UUID.randomUUID();
        BankOffer existing = new BankOffer("Bank", new BigDecimal("9.50"), new BigDecimal("10.00"), 360, true);
        when(repository.findById(id)).thenReturn(java.util.Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RabbitEventPublisher rabbit = mock(RabbitEventPublisher.class);
        AtomicInteger published = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            published.incrementAndGet();
            return null;
        }).when(rabbit).publish(any(), any());

        new ApplicationContextRunner()
                .withBean(PlatformTransactionManager.class, StubTransactionManager::new)
                .withBean(TransactionTemplate.class, () -> new TransactionTemplate(new StubTransactionManager()))
                .withBean(TransactionalEventListenerFactory.class, TransactionalEventListenerFactory::new)
                .withBean(BankOfferRepository.class, () -> repository)
                .withBean(OfferCache.class, () -> cache)
                .withBean(RabbitEventPublisher.class, () -> rabbit)
                .withBean(OfferService.class)
                .withBean(OfferRateChangedPublisher.class)
                .run(context -> {
                    OfferService service = context.getBean(OfferService.class);
                    TransactionTemplate template = context.getBean(TransactionTemplate.class);

                    UpdateOfferRequest req = new UpdateOfferRequest("Bank", new BigDecimal("10.00"), new BigDecimal("10.00"), 360, true);

                    template.executeWithoutResult(status -> {
                        service.update(id, req);
                        assertThat(published.get()).isZero();
                    });

                    assertThat(published.get()).isEqualTo(1);
                });
    }
}