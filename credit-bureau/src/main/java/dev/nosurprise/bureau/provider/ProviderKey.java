package dev.nosurprise.bureau.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * API-ключ провайдера (CREDIT_BUREAU.md §6.2). Хранится только sha256 секретной части: утечка базы
 * не даёт рабочих ключей. Отзыв — revokedAt; строка не удаляется, на неё ссылается журнал запросов.
 */
@Entity
@Table(name = "provider_keys")
public class ProviderKey {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "key_prefix", nullable = false, unique = true)
    private String prefix;

    @Column(name = "key_hash", nullable = false)
    private String hash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ProviderKey() {
    }

    public ProviderKey(UUID providerId, String prefix, String hash, Instant now) {
        this.id = UUID.randomUUID();
        this.providerId = providerId;
        this.prefix = prefix;
        this.hash = hash;
        this.createdAt = now;
    }

    void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getHash() {
        return hash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
