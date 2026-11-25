package dev.nosurprise.bureau.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Провайдер — внешний банк, с которым договорились о доступе к кредитной истории (CREDIT_BUREAU.md §6).
 * Лимиты запросов — свои у каждого провайдера (тариф).
 */
@Entity
@Table(name = "providers")
public class Provider {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderStatus status;

    @Column(nullable = false)
    private boolean sandbox;

    @Column(name = "requests_per_minute", nullable = false)
    private int requestsPerMinute;

    @Column(name = "requests_per_day", nullable = false)
    private int requestsPerDay;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Provider() {
    }

    public Provider(String name, boolean sandbox, int requestsPerMinute, int requestsPerDay, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.status = ProviderStatus.ACTIVE;
        this.sandbox = sandbox;
        this.requestsPerMinute = requestsPerMinute;
        this.requestsPerDay = requestsPerDay;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String name, int requestsPerMinute, int requestsPerDay, Instant now) {
        this.name = name;
        this.requestsPerMinute = requestsPerMinute;
        this.requestsPerDay = requestsPerDay;
        this.updatedAt = now;
    }

    void changeStatus(ProviderStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ProviderStatus getStatus() {
        return status;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public int getRequestsPerDay() {
        return requestsPerDay;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
