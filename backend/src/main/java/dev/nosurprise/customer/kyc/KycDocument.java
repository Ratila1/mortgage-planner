package dev.nosurprise.customer.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Фото паспорта в Postgres (MICROLOANS.md §3.11). Отдельная сущность, а не поле профиля:
 * так списки и профиль не тянут мегабайты. Метаданные читаются проекцией {@link KycMetadata}.
 */
@Entity
@Table(name = "kyc_documents")
public class KycDocument {

    public static final String TYPE_PASSPORT = "PASSPORT";

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String type;

    /**
     * byte[] + VARBINARY → bytea. Без @Lob: на PostgreSQL Hibernate маппит @Lob byte[] в oid
     * (large objects), которые остаются мусором в БД и не чистятся вместе со строкой (§6).
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "content")
    private byte[] content;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected KycDocument() {
    }

    KycDocument(UUID userId, byte[] content, String contentType, String sha256, Instant uploadedAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.type = TYPE_PASSPORT;
        this.content = content;
        this.contentType = contentType;
        this.sizeBytes = content.length;
        this.sha256 = sha256;
        this.uploadedAt = uploadedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getContent() {
        return content;
    }

    public String getContentType() {
        return contentType;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
