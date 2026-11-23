package dev.nosurprise.sms.domain;

import dev.nosurprise.sms.provider.SmsSender.SendResult;
import jakarta.persistence.*;

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

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    public static SmsAttempt of(SmsRequest req, SendResult result) {
        SmsAttempt attempt = new SmsAttempt();
        attempt.smsRequestId = req.getId();
        attempt.attemptNo = req.getAttempts();
        attempt.result = result.ok() ? "SUCCESS" : "FAIL";
        return attempt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getSmsRequestId() { return smsRequestId; }
    public void setSmsRequestId(UUID smsRequestId) { this.smsRequestId = smsRequestId; }

    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
}
