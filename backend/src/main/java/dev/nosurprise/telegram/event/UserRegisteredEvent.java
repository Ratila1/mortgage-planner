package dev.nosurprise.telegram.event;

import java.util.UUID;

public record UserRegisteredEvent(UUID userId, String email) {}
