package dev.nosurprise.notification.event;

import java.time.Instant;
import java.util.UUID;

public record ScenarioRecalculatedMailRequested(UUID mailEventId, Instant occurredAt, String to, String subject, String body) {
}