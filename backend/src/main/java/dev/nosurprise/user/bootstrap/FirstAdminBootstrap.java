package dev.nosurprise.user.bootstrap;

import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Создание первого ADMIN при старте (§6.1, §7): из APP_ADMIN_EMAIL / APP_ADMIN_PASSWORD.
 * Пароль не хранится в миграциях; при отсутствии env — безопасный пропуск.
 * Существующий пользователь не теряет пароль/provider/роли; ADMIN-роль добавляется при её отсутствии.
 * <p>
 * Повышение существующего пользователя выполняется только если его email уже подтверждён:
 * env-переменные могут утечь (CI-логи, манифесты, compose), и атакующий, заранее
 * зарегистрировавший APP_ADMIN_EMAIL, не должен получить ADMIN при рестарте приложения.
 * Существующий непроверенный email → warning и никаких изменений.
 */
@Component
public class FirstAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdminBootstrap.class);

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Value("${APP_ADMIN_EMAIL:}")
    private String adminEmail;

    @Value("${APP_ADMIN_PASSWORD:}")
    private String adminPassword;

    public FirstAdminBootstrap(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            return;
        }
        String email = adminEmail.trim().toLowerCase(Locale.ROOT);
        UserEntity existing = userService.findByEmail(email).orElse(null);
        if (existing != null) {
            if (!existing.isEmailVerified()) {
                // email из env занят непроверенным аккаунтом: повышение — только владельцу адреса.
                log.warn("admin bootstrap: email {} exists but is not verified, ADMIN role not granted", email);
                return;
            }
            userService.ensureAdminRole(existing);
            return;
        }
        userService.createAdminUser(email, passwordEncoder.encode(adminPassword));
    }
}