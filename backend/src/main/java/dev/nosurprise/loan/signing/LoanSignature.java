package dev.nosurprise.loan.signing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Доказательство подписи (MICROLOANS.md §3.10): кто, когда, с какого телефона и IP подписал
 * документ с каким хэшем.
 */
@Entity
@Table(name = "loan_signatures")
public class LoanSignature {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, unique = true)
    private UUID applicationId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "document_sha256", nullable = false)
    private String documentSha256;

    @Column(nullable = false)
    private String phone;

    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "signed_at", nullable = false)
    private Instant signedAt;

    protected LoanSignature() {
    }

    LoanSignature(UUID applicationId, UUID documentId, String documentSha256, String phone, String ip,
                  String userAgent, Instant signedAt) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.documentId = documentId;
        this.documentSha256 = documentSha256;
        this.phone = phone;
        this.ip = ip;
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 512));
        this.signedAt = signedAt;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public Instant getSignedAt() {
        return signedAt;
    }
}
