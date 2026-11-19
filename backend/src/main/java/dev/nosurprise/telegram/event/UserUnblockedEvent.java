package dev.nosurprise.telegram.event;

import java.util.UUID;

public record UserUnblockedEvent(UUID userId) {}
