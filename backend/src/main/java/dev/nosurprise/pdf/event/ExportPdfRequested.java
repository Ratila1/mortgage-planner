package dev.nosurprise.pdf.event;

import java.time.Instant;
import java.util.UUID;

public record ExportPdfRequested(UUID eventId, Instant occurredAt, UUID scenarioId, String calcId, UUID userId) {
}