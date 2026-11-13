package dev.nosurprise.user.bootstrap;

import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Создание первого ADMIN при старте (§6.1, §7): из APP_ADMIN_EMAIL / APP_ADMIN_PASSWORD.
 * Пароль не хранится в миграциях; при отсутствии env — безопасный пропуск.
 * Существующий пользователь не теряет пароль/provider/роли; ADMIN-роль добавляется при её отсутствии.
 */
@Component
public class FirstAdminBootstrap implements ApplicationRunner {

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
            userService.ensureAdminRole(existing);
            return;
        }
        userService.createAdminUser(email, passwordEncoder.encode(adminPassword));
    }
}