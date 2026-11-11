package dev.nosurprise.user.web.dto;

import dev.nosurprise.user.Role;
import dev.nosurprise.user.UserEntity;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GET /api/me (§9): только публичные поля — id, email, emailVerified, roles.
 * password_hash, provider/provider_id и другие внутренние поля пользователя наружу не идут.
 */
public record MeResponse(UUID id, String email, boolean emailVerified, Set<String> roles) {

    public static MeResponse from(UserEntity user) {
        return new MeResponse(user.getId(), user.getEmail(), user.isEmailVerified(),
                user.getRoles().stream().map(Role::name).collect(Collectors.toSet()));
    }
}
