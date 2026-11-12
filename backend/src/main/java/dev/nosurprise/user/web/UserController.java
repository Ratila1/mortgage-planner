package dev.nosurprise.user.web;

import dev.nosurprise.user.UserNotFoundException;
import dev.nosurprise.user.UserService;
import dev.nosurprise.user.web.dto.MeResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * GET /api/me (§9): только для аутентифицированного пользователя (SecurityConfig: остальное —
 * authenticated). subject JWT — userId. Контроллер ходит только в UserService, наружу —
 * DTO без внутренних полей.
 */
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/api/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException ex) {
            throw new UserNotFoundException();
        }
        return userService.findById(userId)
                .map(MeResponse::from)
                .orElseThrow(UserNotFoundException::new);
    }
}
