package dev.nosurprise.scenario.event;

import java.time.Instant;
import java.util.UUID;

/**
 * In-app событие удаления сценария. PdfCleanupListener удаляет PDF'ы сценария из GridFS
 * и связанные PDF_READY-уведомления. Публикуется до коммита — слушает AFTER_COMMIT.
 */
public record ScenarioDeleted(UUID eventId, Instant occurredAt, UUID userId, UUID scenarioId) {
}
