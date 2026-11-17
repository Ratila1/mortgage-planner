package dev.nosurprise.user;

import java.util.UUID;

/** Внутреннее событие приложения: пользователь заблокирован. Слушает auth, чтобы отозвать refresh-токены. */
public record UserBlocked(UUID userId) {
}
