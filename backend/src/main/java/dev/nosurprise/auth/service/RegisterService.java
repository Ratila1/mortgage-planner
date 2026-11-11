package dev.nosurprise.auth.service;

import dev.nosurprise.auth.event.VerificationEmailRequested;
import dev.nosurprise.auth.web.dto.RegisterRequest;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Регистрация (§7, §9): email + пароль → user(email_verified=false) + verify:{token} в Redis
 * → письмо через mail.requested. Повторный email НЕ считается ошибкой: ответ одинаковый
 * для нового и существующего адреса — существование email не раскрывается; для
 * существующего email ничего не создаётся и событие не публикуется.
 * Событие VerificationEmailRequested публикуется внутри транзакции; в Rabbit оно уйдёт
 * после коммита (мост слушает AFTER_COMMIT, §8).
 */
@Service
public class RegisterService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final VerificationTokenStore tokenStore;
    private final ApplicationEventPublisher eventPublisher;

    public RegisterService(UserService userService, PasswordEncoder passwordEncoder,
                           VerificationTokenStore tokenStore, ApplicationEventPublisher eventPublisher) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userService.findByEmail(email).isPresent()) {
            return;
        }
        String passwordHash = passwordEncoder.encode(request.password());
        UserEntity user = userService.createLocalUser(email, passwordHash);
        String token = tokenStore.create(user.getId());
        eventPublisher.publishEvent(new VerificationEmailRequested(user.getId(), email, token));
    }
}
