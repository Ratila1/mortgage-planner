package dev.nosurprise.integration;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.common.outbox.OutboxRelay;
import dev.nosurprise.config.RabbitMailConfig;
import dev.nosurprise.integration.support.LoanItSupport;
import dev.nosurprise.loan.application.LoanApplicationEvent;
import dev.nosurprise.loan.application.LoanHistoryChanged;
import dev.nosurprise.loan.history.CreditHistoryDocument;
import dev.nosurprise.loan.history.CreditHistoryStore;
import dev.nosurprise.loan.jobs.CreditHistoryReconcileJob;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Согласованность: outbox (§4.8) и копия кредитной истории в Mongo (§4.9). */
@DisplayName("Микрозайм: outbox и согласованность Postgres ↔ Mongo")
class LoanConsistencyIntegrationTest extends LoanItSupport {

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private CreditHistoryStore store;

    @Autowired
    private CreditHistoryReconcileJob reconcileJob;

    @Test
    @DisplayName("откат транзакции → события в outbox нет")
    void rollbackLeavesNoEvent() {
        UUID eventId = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            outbox.publish("loan.history.changed", "test", "x", new LoanHistoryChanged(eventId, Instant.now(),
                    UUID.randomUUID()));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_id = ?", Integer.class, eventId))
                .isZero();
    }

    @Test
    @DisplayName("вне транзакции outbox не пишет (MANDATORY)")
    void publishRequiresTransaction() {
        assertThatThrownBy(() -> outbox.publish("loan.history.changed", "test", "x",
                new LoanHistoryChanged(UUID.randomUUID(), Instant.now(), UUID.randomUUID())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("relay отправляет событие и ставит published_at")
    void relayPublishes() {
        Client client = readyClient("outbox-relay");
        JsonNode application = submitOk(client);
        relay.relayBatch();

        await().atMost(ASYNC).until(() -> jdbc.queryForObject("select count(*) from outbox_events "
                + "where aggregate_id = ? and published_at is null", Integer.class, id(application)) == 0);
    }

    @Test
    @DisplayName("повторная доставка одного события → одно уведомление")
    void duplicateDeliveryCreatesOneNotification() throws InterruptedException {
        Client client = readyClient("outbox-dup");
        JsonNode application = submitOk(client);
        reject(adminToken(), application, "дубль");
        await().atMost(ASYNC).until(() -> notifications(client.id(), "LOAN_REJECTED") == 1);

        LoanApplicationEvent duplicate = new LoanApplicationEvent(UUID.randomUUID(), Instant.now(),
                UUID.fromString(id(application)), client.id(), "REJECTED");
        rabbitTemplate.convertAndSend(RabbitMailConfig.EVENTS_EXCHANGE, "loan.application.rejected", duplicate);
        rabbitTemplate.convertAndSend(RabbitMailConfig.EVENTS_EXCHANGE, "loan.application.rejected", duplicate);

        await().atMost(ASYNC).until(() -> notifications(client.id(), "LOAN_REJECTED") == 2);
        Thread.sleep(Duration.ofSeconds(2));
        assertThat(notifications(client.id(), "LOAN_REJECTED")).isEqualTo(2);
    }

    @Test
    @DisplayName("старое состояние после нового не откатывает документ в Mongo")
    void olderStateDoesNotOverwrite() {
        Client client = readyClient("mongo-order");
        JsonNode application = submitOk(client);
        String applicationId = id(application);
        reject(adminToken(), application, "порядок");
        await().atMost(ASYNC).until(() -> {
            Document history = creditHistory(applicationId);
            return history != null && "REJECTED".equals(history.getString("status"));
        });
        CreditHistoryDocument current = mongo.findById(UUID.fromString(applicationId), CreditHistoryDocument.class);

        CreditHistoryDocument older = new CreditHistoryDocument(current.id(), current.userId(), 1,
                current.lastEventId() - 1, "SUBMITTED", current.loan(), current.monthlyIncome(),
                current.debtLoadPercent(), current.submittedAt(), null, null, null, null, current.timeline(),
                current.incidents(), Instant.now());

        assertThat(store.replaceIfNewer(older)).isFalse();
        assertThat(creditHistory(applicationId).getString("status")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("сверка догоняет заявку, событие которой потерялось")
    void reconcileCatchesUp() {
        Client client = readyClient("mongo-reconcile");
        JsonNode application = submitOk(client);
        String applicationId = id(application);
        await().atMost(ASYNC).until(() -> creditHistory(applicationId) != null);

        // Имитация: сообщение ушло в DLQ — документа нет, отметки нет, последнее событие давно.
        mongo.remove(Query.query(Criteria.where("_id").is(UUID.fromString(applicationId))), "credit_history");
        jdbc.update("update loan_applications set credit_history_synced_event_id = null where id = ?::uuid",
                applicationId);
        jdbc.update("update loan_events set occurred_at = now() - interval '1 hour' where application_id = ?::uuid",
                applicationId);

        assertThat(reconcileJob.republishLagging()).isGreaterThanOrEqualTo(1);
        await().atMost(ASYNC).until(() -> creditHistory(applicationId) != null);
    }

    @Test
    @DisplayName("пересборка из Postgres возвращает удалённые документы")
    void rebuildRestoresDocuments() {
        Client client = readyClient("mongo-rebuild");
        JsonNode application = submitOk(client);
        String applicationId = id(application);
        await().atMost(ASYNC).until(() -> creditHistory(applicationId) != null);
        mongo.remove(Query.query(Criteria.where("userId").is(client.id())), "credit_history");

        assertThat(api.postEmpty("/api/admin/credit-history/rebuild", adminToken()).getStatusCode().is2xxSuccessful())
                .isTrue();

        assertThat(creditHistory(applicationId)).isNotNull();
        assertThat(creditHistory(applicationId).getString("status")).isEqualTo("SUBMITTED");
    }
}
