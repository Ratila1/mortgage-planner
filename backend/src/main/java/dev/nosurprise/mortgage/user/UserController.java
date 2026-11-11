package dev.nosurprise.mortgage.user;

import dev.nosurprise.mortgage.auth.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<UserProfileResponse> getProfile(@AuthenticationPrincipal User user) {
        return userService.getCurrentUser(user.email())
                .map(u -> new UserProfileResponse(u.id(), u.email(), u.name(), u.emailVerified(), u.roles()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<UserProfileResponse> updateProfile(@AuthenticationPrincipal User user,
                                                    @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(user.email(), request.name(), request.newEmail())
                .map(u -> new UserProfileResponse(u.id(), u.email(), u.name(), u.emailVerified(), u.roles()));
    }

    public record UserProfileResponse(
            Long id,
            String email,
            String name,
            boolean emailVerified,
            java.util.Set<dev.nosurprise.mortgage.auth.Role> roles
    ) {}

    public record UpdateProfileRequest(
            @NotBlank @Size(min = 1, max = 255) String name,
            String newEmail
    ) {}
}







