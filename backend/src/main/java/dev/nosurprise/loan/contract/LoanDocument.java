package dev.nosurprise.loan.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Договор по заявке (MICROLOANS.md §3.10). Сам PDF лежит в GridFS, здесь — ссылка и SHA-256:
 * подписывается именно документ с этим хэшем.
 */
@Entity
@Table(name = "loan_documents")
public class LoanDocument {

    public static final String TYPE_CONTRACT = "CONTRACT";

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private String type;

    @Column(name = "gridfs_id", nullable = false)
    private String gridfsId;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanDocument() {
    }

    LoanDocument(UUID applicationId, String gridfsId, String sha256, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.type = TYPE_CONTRACT;
        this.gridfsId = gridfsId;
        this.sha256 = sha256;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getType() {
        return type;
    }

    public String getGridfsId() {
        return gridfsId;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
