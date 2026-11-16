package dev.nosurprise.notification;

import dev.nosurprise.notification.web.dto.NotificationResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    /**
     * Оптимистичный дедуп (§8): вставка в отдельной транзакции (REQUIRES_NEW).
     * Конфликт уникального индекса V8 откатывает только вставку — транзакция
     * вызывающего consumer'а (и его claim в Redis) не затрагивается.
     */
    private final TransactionTemplate dedupTemplate;

    public NotificationService(NotificationRepository repository, ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.dedupTemplate = new TransactionTemplate(transactionManager);
        this.dedupTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        // Insert-first вместо полного скана (§8): дедуп держит уникальный частичный
        // индекс V8, скан нужен только в редкой ветке конфликта (гонка consumer'ов).
        String payload = buildPayload(scenarioId, calcId, fileId, fileName);
        try {
            return insertNotification(userId, PDF_READY, payload);
        } catch (DataIntegrityViolationException ex) {
            Notification existing = findExisting(userId, PDF_READY, scenarioId, calcId);
            if (existing != null) {
                return existing;
            }
            throw ex;
        }
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

    private Notification insertNotification(UUID userId, String type, String payload) {
        Notification notification = new Notification(userId, type, payload);
        return dedupTemplate.execute(status -> repository.save(notification));
    }

    private Notification findExisting(UUID userId, String type, UUID scenarioId, String calcId) {
        for (Notification existing : repository.findByUserIdAndType(userId, type)) {
            if (isSameScenario(existing, scenarioId, calcId)) {
                return existing;
            }
        }
        return null;
    }

    private boolean isSameScenario(Notification notification, UUID scenarioId, String calcId) {
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
        String payload = buildScenarioRecalculatedPayload(scenarioId, calcId, oldRate, newRate,
                oldMonthlyPayment, newMonthlyPayment, oldOverpayment, newOverpayment);
        try {
            return insertNotification(userId, "SCENARIO_RECALCULATED", payload);
        } catch (DataIntegrityViolationException ex) {
            Notification existing = findExisting(userId, "SCENARIO_RECALCULATED", scenarioId, calcId);
            if (existing != null) {
                return existing;
            }
            throw ex;
        }
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

    /**
     * При удалении сценария убираем его PDF_READY-уведомления (§8):
     * файл в GridFS уже не существует — уведомление с ссылкой на скачивание вводит в заблуждение.
     */
    @Transactional
    public void deletePdfReadyForScenario(UUID userId, UUID scenarioId) {
        repository.deletePdfReadyForScenario(userId, scenarioId.toString());
    }

    private boolean isSameScenarioRecalc(Notification notification, UUID scenarioId, String calcId) {
        return isSameScenario(notification, scenarioId, calcId);
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