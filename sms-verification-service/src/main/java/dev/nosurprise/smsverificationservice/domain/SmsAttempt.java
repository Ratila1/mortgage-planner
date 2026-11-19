package dev.nosurprise.smsverificationservice.domain;

import dev.nosurprise.smsverificationservice.provider.DispatchSmsProvider.SendResult;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sms_attempt", schema = "sms")
public class SmsAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sms_attempt_gen")
    @SequenceGenerator(name = "sms_attempt_gen", sequenceName = "sms_attempt_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "sms_request_id", nullable = false)
    private UUID smsRequestId;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    public static SmsAttempt of(SmsRequest req, SendResult result, int latencyMs) {
        SmsAttempt attempt = new SmsAttempt();
        attempt.smsRequestId = req.getId();
        attempt.attemptNo = req.getAttempts();
        attempt.startedAt = Instant.now();
        attempt.finishedAt = Instant.now();
        attempt.result = result.success() ? "SUCCESS" : "FAIL";
        attempt.errorCode = result.success() ? null : result.errorCode();
        attempt.errorMessage = result.success() ? null : result.errorCode();
        attempt.providerMessageId = result.providerMessageId();
        attempt.latencyMs = latencyMs;
        return attempt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getSmsRequestId() { return smsRequestId; }
    public void setSmsRequestId(UUID smsRequestId) { this.smsRequestId = smsRequestId; }

    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
}