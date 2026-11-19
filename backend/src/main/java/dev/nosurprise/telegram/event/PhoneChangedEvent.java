package dev.nosurprise.telegram.event;

import java.util.UUID;

public record PhoneChangedEvent(UUID userId, String oldPhone, String newPhone) {}
