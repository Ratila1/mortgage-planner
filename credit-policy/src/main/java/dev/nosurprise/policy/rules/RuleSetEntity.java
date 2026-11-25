package dev.nosurprise.policy.rules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Строка rule_sets: версия правил целиком в jsonb. Содержимое версии не меняется — только флаг active. */
@Entity
@Table(name = "rule_sets")
public class RuleSetEntity {

    @Id
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String rules;

    @Column(nullable = false)
    private boolean active;

    private String comment;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RuleSetEntity() {
    }

    public RuleSetEntity(int version, String rules, String comment, UUID createdBy, Instant createdAt) {
        this.version = version;
        this.rules = rules;
        this.comment = comment;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    public int getVersion() {
        return version;
    }

    public String getRules() {
        return rules;
    }

    public boolean isActive() {
        return active;
    }

    public String getComment() {
        return comment;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
