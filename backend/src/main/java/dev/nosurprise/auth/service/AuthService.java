package dev.nosurprise.auth.service;

import dev.nosurprise.user.Role;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;

/**
 * Логин / refresh / logout (§7, §9).
 * - Логин: неверный email ИЛИ неверный пароль — одна и та же ошибка InvalidCredentialsException
 *   (существование email не раскрывается); вход до подтверждения почты — EmailNotVerifiedException.
 * - Refresh: ротация через RefreshTokenService (старый токен удаляется, новый создаётся).
 * - Logout: удаляет текущий refresh-токен; отсутствие cookie — идемпотентно.
 * Access JWT нигде не хранится (PostgreSQL/Redis), только в ответе API.
 */
@Service
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder,
                       TokenService tokenService, RefreshTokenService refreshTokenService,
                       JwtProperties jwtProperties, Clock clock) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    public LoginResult login(String email, String password, String issuedIp) {
        UserEntity user = userService.findByEmail(normalize(email))
                .orElseThrow(InvalidCredentialsException::new);
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        return issueTokens(user, issuedIp);
    }

    public LoginResult refresh(String refreshCookieValue, String issuedIp) {
        if (refreshCookieValue == null) {
            throw new InvalidRefreshTokenException();
        }
        IssuedRefreshToken rotated = refreshTokenService.rotate(refreshCookieValue, issuedIp);
        java.util.Set<Role> roles = userService.findById(rotated.userId())
                .map(dev.nosurprise.user.UserEntity::getRoles)
                .orElse(java.util.Set.of());
        return new LoginResult(
                tokenService.generate(rotated.userId(), roles),
                jwtProperties.accessTtl().toSeconds(),
                rotated.cookieValue());
    }

    /** Отзыв текущего refresh-токена. Без cookie ничего не делает (идемпотентно). */
    public void logout(String refreshCookieValue) {
        if (refreshCookieValue != null) {
            refreshTokenService.revoke(refreshCookieValue);
        }
    }

    private LoginResult issueTokens(UserEntity user, String issuedIp) {
        IssuedRefreshToken refresh = refreshTokenService.issue(user.getId(), issuedIp);
        return new LoginResult(
                tokenService.generate(user.getId(), user.getRoles()),
                jwtProperties.accessTtl().toSeconds(),
                refresh.cookieValue());
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
