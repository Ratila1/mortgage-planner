package dev.nosurprise.customer.web;

import dev.nosurprise.customer.CustomerService;
import dev.nosurprise.customer.PhoneVerificationService;
import dev.nosurprise.customer.web.dto.CodeRequest;
import dev.nosurprise.customer.web.dto.ProfileRequest;
import dev.nosurprise.customer.web.dto.ProfileResponse;
import dev.nosurprise.user.UserService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Профиль клиента в личном кабинете и подтверждение телефона (MICROLOANS.md §3.13, §4.6). */
@RestController
@RequestMapping("/api/me")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class ProfileController {

    private final CustomerService customerService;
    private final PhoneVerificationService phoneVerification;
    private final UserService userService;

    public ProfileController(CustomerService customerService, PhoneVerificationService phoneVerification,
                             UserService userService) {
        this.customerService = customerService;
        this.phoneVerification = phoneVerification;
        this.userService = userService;
    }

    /** 204, если профиль ещё не заполнен: для ЛК это нормальное состояние, а не ошибка. */
    @GetMapping("/profile")
    ResponseEntity<ProfileResponse> get(@AuthenticationPrincipal Jwt jwt) {
        return customerService.findProfile(userId(jwt))
                .map(view -> ResponseEntity.ok(ProfileResponse.from(view)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/profile")
    ProfileResponse save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request) {
        UUID userId = userId(jwt);
        userService.assertNotBlocked(userId);
        return ProfileResponse.from(customerService.saveProfile(userId, request.toData()));
    }

    @PostMapping("/phone/verification")
    ResponseEntity<Void> requestCode(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = userId(jwt);
        userService.assertNotBlocked(userId);
        phoneVerification.requestCode(userId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/phone/verification/confirm")
    ResponseEntity<Void> confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest request) {
        phoneVerification.confirm(userId(jwt), request.code());
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
