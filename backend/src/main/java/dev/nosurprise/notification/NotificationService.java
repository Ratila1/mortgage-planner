package dev.nosurprise.notification;

import dev.nosurprise.notification.web.dto.NotificationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    public static final String PDF_READY = "PDF_READY";

    private final NotificationRepository repository;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(UUID userId) {
        // Alias-free public method; keep name stable for controllers/tests.
        return repository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .limit(100)
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification notification = repository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new NotificationNotFoundException("notification not found: " + notificationId));
        if (!notification.isRead()) {
            notification.setRead(true);
            repository.save(notification);
        }
    }

    private NotificationResponse toResponse(Notification notification) {
        tools.jackson.databind.JsonNode payload;
        try {
            payload = objectMapper.readTree(notification.getPayload());
        } catch (RuntimeException ex) {
            payload = null;
        }
        return new NotificationResponse(notification.getId(), notification.getType(), payload,
                notification.isRead(), notification.getCreatedAt());
    }

    @Transactional
    public Notification createPdfReady(UUID userId, UUID scenarioId, String calcId, String fileId, String fileName) {
        return createPdfReadyNotification(userId, scenarioId, calcId, fileId, fileName);
    }

    @Transactional
    public Notification createPdfReadyNotification(UUID userId, UUID scenarioId, String calcId, String fileId, String fileName) {
        for (Notification existing : repository.findByUserIdAndType(userId, PDF_READY)) {
            if (isSamePdf(existing, scenarioId, calcId)) {
                return existing;
            }
        }
        String payload = buildPayload(scenarioId, calcId, fileId, fileName);
        Notification notification = new Notification(userId, PDF_READY, payload);
        return repository.save(notification);
    }

    @Transactional
    public Notification notifyPdfReady(UUID userId, UUID scenarioId, String calcId, String fileId, String fileName) {
        return createPdfReadyNotification(userId, scenarioId, calcId, fileId, fileName);
    }

    @Transactional
    public Notification createPdfReady(UUID userId, UUID scenarioId, String calcId,
                                     org.bson.types.ObjectId fileId, String fileName) {
        return createPdfReadyNotification(userId, scenarioId, calcId, fileId == null ? null : fileId.toHexString(), fileName);
    }

    private boolean isSamePdf(Notification notification, UUID scenarioId, String calcId) {
        try {
            JsonNode node = objectMapper.readTree(notification.getPayload());
            UUID existingScenario = node.has("scenarioId") ? UUID.fromString(node.get("scenarioId").asText()) : null;
            String existingCalc = node.has("calcId") ? node.get("calcId").asText() : null;
            return scenarioId.equals(existingScenario) && calcId.equals(existingCalc);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Transactional
    public Notification createScenarioRecalculated(UUID userId, UUID scenarioId, String calcId,
                                                  BigDecimal oldRate, BigDecimal newRate,
                                                  BigDecimal oldMonthlyPayment, BigDecimal newMonthlyPayment,
                                                  BigDecimal oldOverpayment, BigDecimal newOverpayment) {
        for (Notification existing : repository.findByUserIdAndType(userId, "SCENARIO_RECALCULATED")) {
            if (isSameScenarioRecalc(existing, scenarioId, calcId)) {
                return existing;
            }
        }
        String payload = buildScenarioRecalculatedPayload(scenarioId, calcId, oldRate, newRate,
                oldMonthlyPayment, newMonthlyPayment, oldOverpayment, newOverpayment);
        return repository.save(new Notification(userId, "SCENARIO_RECALCULATED", payload));
    }

    @Transactional
    public Notification createNotification(UUID userId, String type, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (RuntimeException ex) {
            json = String.valueOf(payload);
        }
        return repository.save(new Notification(userId, type, json));
    }

    private boolean isSameScenarioRecalc(Notification notification, UUID scenarioId, String calcId) {
        try {
            JsonNode node = objectMapper.readTree(notification.getPayload());
            UUID existingScenario = node.has("scenarioId") ? UUID.fromString(node.get("scenarioId").asText()) : null;
            String existingCalc = node.has("calcId") ? node.get("calcId").asText() : null;
            return scenarioId.equals(existingScenario) && calcId.equals(existingCalc);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String buildScenarioRecalculatedPayload(UUID scenarioId, String calcId, BigDecimal oldRate, BigDecimal newRate,
                                                   BigDecimal oldMonthlyPayment, BigDecimal newMonthlyPayment,
                                                   BigDecimal oldOverpayment, BigDecimal newOverpayment) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scenarioId", scenarioId.toString());
        node.put("calcId", calcId);
        node.put("oldRate", oldRate.toPlainString());
        node.put("newRate", newRate.toPlainString());
        node.put("oldMonthlyPayment", oldMonthlyPayment.toPlainString());
        node.put("newMonthlyPayment", newMonthlyPayment.toPlainString());
        node.put("oldOverpayment", oldOverpayment.toPlainString());
        node.put("newOverpayment", newOverpayment.toPlainString());
        return node.toString();
    }

    private String buildPayload(UUID scenarioId, String calcId, String fileId, String fileName) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scenarioId", scenarioId.toString());
        node.put("calcId", calcId);
        node.put("fileId", fileId);
        node.put("fileName", fileName);
        node.put("downloadUrl", "/api/scenarios/" + scenarioId + "/pdf");
        return node.toString();
    }
}