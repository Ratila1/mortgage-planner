package dev.nosurprise.auth.service;

import dev.nosurprise.user.UserBlocked;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * При блокировке пользователя удаляет все его refresh-токены (MICROLOANS.md §3.5).
 * AFTER_COMMIT: если блокировка откатилась, токены остаются. Access JWT доживает до 15 минут — окно принято.
 */
@Component
public class RevokeTokensOnUserBlocked {

    private final RefreshTokenStore store;

    public RevokeTokensOnUserBlocked(RefreshTokenStore store) {
        this.store = store;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(UserBlocked event) {
        store.deleteAll(event.userId());
    }
}
