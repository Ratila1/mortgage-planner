package dev.nosurprise.smsverificationservice.telegram.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telegram_bindings", schema = "sms")
public class TelegramBinding {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String phone;

    @Column(name = "telegram_id", nullable = false, unique = true)
    private Long telegramId;

    private String username;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected TelegramBinding() {}

    public TelegramBinding(UUID userId, String phone, Long telegramId, String username, Instant now) {
        this.userId = userId;
        this.phone = phone;
        this.telegramId = telegramId;
        this.username = username;
        this.linkedAt = now;
        this.updatedAt = now;
    }

    public UUID getUserId() { return userId; }
    public String getPhone() { return phone; }
    public Long getTelegramId() { return telegramId; }
    public String getUsername() { return username; }
    public Instant getLinkedAt() { return linkedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setPhone(String phone) { this.phone = phone; }
    public void setTelegramId(Long telegramId) { this.telegramId = telegramId; }
    public void setUsername(String username) { this.username = username; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
