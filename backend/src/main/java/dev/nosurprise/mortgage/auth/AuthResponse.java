package dev.nosurprise.mortgage.auth;

import java.util.Set;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        Long userId,
        String email,
        String name,
        Set<Role> roles
) {}







