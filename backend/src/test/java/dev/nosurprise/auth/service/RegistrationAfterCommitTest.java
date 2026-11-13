package dev.nosurprise.auth.service;

import dev.nosurprise.auth.event.VerificationEmailRequested;
import dev.nosurprise.auth.web.dto.RegisterRequest;
import dev.nosurprise.user.Provider;
import dev.nosurprise.user.Role;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * mail.requested публикуется ТОЛЬКО после коммита транзакции (§8): событие пишется
 * RegisterService внутри транзакции, а слушатель @TransactionalEventListener(AFTER_COMMIT)
 * получает его после commit; при откате транзакции событие не доставляется.
 * Реальный Spring-механизм синхронизаций проверяется через ApplicationContextRunner
 * со stub PlatformTransactionManager (без БД).
 */
class RegistrationAfterCommitTest {

    /** Транзакция без БД: commit вызывает только после-коммитные синхронизации Spring. */
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

    /** Слушатель как будущий мост → Rabbit: AFTER_COMMIT, пишет полученные события в список. */
    static class RecordingListener {
        final List<VerificationEmailRequested> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(VerificationEmailRequested event) {
            received.add(event);
        }
    }

    @Test
    @DisplayName("событие письма не публикуется до коммита и публикуется после commit")
    void publishedOnlyAfterCommit() {
        UserService userService = mock(UserService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        VerificationTokenStore tokenStore = mock(VerificationTokenStore.class);
        UUID userId = UUID.randomUUID();

        new ApplicationContextRunner()
                .withBean(PlatformTransactionManager.class, StubTransactionManager::new)
                .withBean(TransactionTemplate.class, () -> new TransactionTemplate(new StubTransactionManager()))
                .withBean(TransactionalEventListenerFactory.class, TransactionalEventListenerFactory::new)
                .withBean(UserService.class, () -> userService)
                .withBean(PasswordEncoder.class, () -> passwordEncoder)
                .withBean(VerificationTokenStore.class, () -> tokenStore)
                .withBean(RegisterService.class)
                .withBean(RecordingListener.class, RecordingListener::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(RegisterService.class);
                    RecordingListener listener = context.getBean(RecordingListener.class);
                    TransactionTemplate template = context.getBean(TransactionTemplate.class);
                    when(userService.findByEmail("user@example.com")).thenReturn(Optional.empty());
                    when(passwordEncoder.encode(anyString())).thenReturn("hash");
                    when(userService.createLocalUser(eq("user@example.com"), anyString()))
                            .thenReturn(new UserEntity(userId, "user@example.com", "hash", false,
                                    Provider.LOCAL, null, Instant.now(), Set.of(Role.USER)));
                    when(tokenStore.create(userId)).thenReturn("token-1");

                    AtomicReference<Integer> receivedDuringTx = new AtomicReference<>();
                    template.executeWithoutResult(status -> {
                        context.getBean(RegisterService.class)
                                .register(new RegisterRequest("user@example.com", "Passw0rd!!"));
                        receivedDuringTx.set(listener.received.size());
                    });

                    assertThat(receivedDuringTx.get()).isZero();
                    assertThat(listener.received).containsExactly(
                            new VerificationEmailRequested(userId, "user@example.com", "token-1"));
                });
    }

    @Test
    @DisplayName("откат транзакции → событие письма не доставляется")
    void notPublishedAfterRollback() {
        UserService userService = mock(UserService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        VerificationTokenStore tokenStore = mock(VerificationTokenStore.class);
        UUID userId = UUID.randomUUID();

        new ApplicationContextRunner()
                .withBean(PlatformTransactionManager.class, StubTransactionManager::new)
                .withBean(TransactionTemplate.class, () -> new TransactionTemplate(new StubTransactionManager()))
                .withBean(TransactionalEventListenerFactory.class, TransactionalEventListenerFactory::new)
                .withBean(UserService.class, () -> userService)
                .withBean(PasswordEncoder.class, () -> passwordEncoder)
                .withBean(VerificationTokenStore.class, () -> tokenStore)
                .withBean(RegisterService.class)
                .withBean(RecordingListener.class, RecordingListener::new)
                .run(context -> {
                    RecordingListener listener = context.getBean(RecordingListener.class);
                    TransactionTemplate template = context.getBean(TransactionTemplate.class);
                    when(userService.findByEmail("user@example.com")).thenReturn(Optional.empty());
                    when(passwordEncoder.encode(anyString())).thenReturn("hash");
                    when(userService.createLocalUser(eq("user@example.com"), anyString()))
                            .thenReturn(new UserEntity(userId, "user@example.com", "hash", false,
                                    Provider.LOCAL, null, Instant.now(), Set.of(Role.USER)));
                    when(tokenStore.create(userId)).thenReturn("token-1");

                    template.execute(status -> {
                        context.getBean(RegisterService.class)
                                .register(new RegisterRequest("user@example.com", "Passw0rd!!"));
                        status.setRollbackOnly();
                        return null;
                    });

                    assertThat(listener.received).isEmpty();
                });
    }
}
