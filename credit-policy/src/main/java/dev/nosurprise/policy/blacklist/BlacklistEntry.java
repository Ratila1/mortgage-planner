package dev.nosurprise.policy.blacklist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Запись чёрного списка (CREDIT_POLICY.md §7.4). Запрещает только новые займы. Ключ — телефон или
 * фамилия + имя (+ отчество, если указано) + дата рождения: новый аккаунт с тем же человеком не поможет.
 * Запись не удаляется, а снимается (removed_at): видно, кто и когда снял.
 */
@Entity
@Table(name = "blacklist_entries")
public class BlacklistEntry {

    @Id
    private UUID id;

    private String phone;

    @Column(name = "last_name_norm")
    private String lastNameNorm;

    @Column(name = "first_name_norm")
    private String firstNameNorm;

    @Column(name = "middle_name_norm")
    private String middleNameNorm;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(nullable = false)
    private String reason;

    @Column(name = "added_by", nullable = false)
    private UUID addedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removed_by")
    private UUID removedBy;

    protected BlacklistEntry() {
    }

    public BlacklistEntry(String phone, String lastNameNorm, String firstNameNorm, String middleNameNorm,
                          LocalDate birthDate, String reason, UUID addedBy, Instant expiresAt, Instant now) {
        this.id = UUID.randomUUID();
        this.phone = phone;
        this.lastNameNorm = lastNameNorm;
        this.firstNameNorm = firstNameNorm;
        this.middleNameNorm = middleNameNorm;
        this.birthDate = birthDate;
        this.reason = reason;
        this.addedBy = addedBy;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    void remove(UUID adminId, Instant now) {
        if (removedAt == null) {
            removedAt = now;
            removedBy = adminId;
        }
    }

    public boolean isActive(Instant now) {
        return removedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public UUID getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    public String getLastNameNorm() {
        return lastNameNorm;
    }

    public String getFirstNameNorm() {
        return firstNameNorm;
    }

    public String getMiddleNameNorm() {
        return middleNameNorm;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getReason() {
        return reason;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public UUID getRemovedBy() {
        return removedBy;
    }
}
