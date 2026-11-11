package dev.nosurprise.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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