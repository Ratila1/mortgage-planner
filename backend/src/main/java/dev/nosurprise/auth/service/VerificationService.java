package dev.nosurprise.auth.service;

import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Подтверждение почты (§9): GET /api/auth/verify-email?token=...
 * Токен сначала читается, пользователь подтверждается, только потом токен удаляется —
 * повторное подтверждение тем же токеном безопасно (идемпотентно: email_verified уже true).
 * Токен отсутствует/истёк или пользователь неизвестен → VerifyTokenInvalidException (400).
 * Не раскрывает, существует ли пользователь.
 */
@Service
public class VerificationService {

    private final VerificationTokenStore tokenStore;
    private final UserService userService;

    public VerificationService(VerificationTokenStore tokenStore, UserService userService) {
        this.tokenStore = tokenStore;
        this.userService = userService;
    }

    @Transactional
    public void verify(String token) {
        var userId = tokenStore.read(token).orElseThrow(VerifyTokenInvalidException::new);
        UserEntity user = userService.findById(userId).orElseThrow(VerifyTokenInvalidException::new);
        if (!user.isEmailVerified()) {
            userService.markEmailVerified(user);
        }
        tokenStore.delete(token);
    }
}
