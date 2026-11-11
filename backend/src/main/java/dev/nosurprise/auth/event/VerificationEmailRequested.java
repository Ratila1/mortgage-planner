package dev.nosurprise.auth.event;

import java.util.UUID;

/**
 * In-app событие «нужно письмо подтверждения почты». Публикуется RegisterService внутри
 * транзакции; мост (RabbitEventPublisher) слушает с @TransactionalEventListener(AFTER_COMMIT)
 * и превращает его в mail.requested (§8). Никогда не уходит в Rabbit сам по себе.
 */
public record VerificationEmailRequested(UUID userId, String email, String token) {
}
