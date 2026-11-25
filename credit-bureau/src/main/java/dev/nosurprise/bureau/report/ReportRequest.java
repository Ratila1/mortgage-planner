package dev.nosurprise.bureau.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка журнала запросов (CREDIT_BUREAU.md §6.1): кто, по каким параметрам (хеш, не сами ФИО и телефон),
 * когда, что ответили и оплачивается ли запрос. По журналу считаются деньги и разбираются споры по reportId.
 */
@Entity
@Table(name = "report_requests")
public class ReportRequest {

    public enum Result { FOUND, NOT_FOUND, AMBIGUOUS }

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "key_id", nullable = false)
    private UUID keyId;

    @Column(name = "search_by", nullable = false)
    private String searchBy;

    @Column(name = "query_hash", nullable = false)
    private String queryHash;

    @Column(nullable = false)
    private String result;

    @Column(nullable = false)
    private boolean billable;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReportRequest() {
    }

    public ReportRequest(UUID id, UUID providerId, UUID keyId, SearchQuery.SearchBy searchBy, String queryHash,
                         Result result, boolean billable, String idempotencyKey, Instant createdAt) {
        this.id = id;
        this.providerId = providerId;
        this.keyId = keyId;
        this.searchBy = searchBy.name();
        this.queryHash = queryHash;
        this.result = result.name();
        this.billable = billable;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getKeyId() {
        return keyId;
    }

    public String getSearchBy() {
        return searchBy;
    }

    public String getResult() {
        return result;
    }

    public boolean isBillable() {
        return billable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
