package dev.nosurprise.customer.web.admin;

import dev.nosurprise.common.Paging;
import dev.nosurprise.customer.CustomerService;
import dev.nosurprise.customer.web.dto.ProfileResponse;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.AccountNotFoundException;
import dev.nosurprise.user.UserService;
import io.micrometer.core.annotation.Timed;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Панель пользователей в админке (MICROLOANS.md §3.5). Живёт в модуле customer: карточке нужны
 * и учётная запись (user), и профиль; customer зависит от user, обратной зависимости нет.
 * Кредитную историю карточка берёт отдельным запросом /api/admin/users/{id}/credit-history (модуль loan).
 */
@RestController
@RequestMapping("/api/admin/users")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class AdminUserController {

    private final AdminUserDirectory directory;
    private final UserService userService;
    private final CustomerService customerService;

    public AdminUserController(AdminUserDirectory directory, UserService userService, CustomerService customerService) {
        this.directory = directory;
        this.userService = userService;
        this.customerService = customerService;
    }

    @GetMapping
    AdminUserDirectory.Page list(@RequestParam(required = false) String query,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return directory.search(query, Paging.page(page), Paging.size(size));
    }

    @GetMapping("/{id}")
    UserCard card(@PathVariable UUID id) {
        UserEntity user = userService.findById(id).orElseThrow(AccountNotFoundException::new);
        ProfileResponse profile = customerService.findProfile(id).map(ProfileResponse::from).orElse(null);
        return new UserCard(user.getId(), user.getEmail(), user.getRoles().stream().map(Enum::name).sorted().toList(),
                user.getProvider().name(), user.isEmailVerified(), user.isBlocked(), user.getCreatedAt(), profile);
    }

    @PostMapping("/{id}/block")
    ResponseEntity<Void> block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        userService.block(id, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unblock")
    ResponseEntity<Void> unblock(@PathVariable UUID id) {
        userService.unblock(id);
        return ResponseEntity.noContent().build();
    }

    public record UserCard(UUID id, String email, List<String> roles, String provider, boolean emailVerified,
                           boolean blocked, Instant createdAt, ProfileResponse profile) {
    }
}
