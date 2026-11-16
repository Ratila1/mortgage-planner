package dev.nosurprise.auth.service;

import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Подтверждение почты + мгновенный вход (§9): POST /api/auth/verify-email {token}.
 * Токен одноразовый: пользователь подтверждается, токен удаляется, сессия выдаётся
 * сразу — отдельно логиниться после перехода по ссылке не нужно. Повторное
 * использование токена → VerifyTokenInvalidException (400) — сессию по старой
 * ссылке не получить. Токен отсутствует/истёк или пользователь неизвестен →
 * VerifyTokenInvalidException. Не раскрывает, существует ли пользователь.
 */
@Service
public class VerificationService {

    private final VerificationTokenStore tokenStore;
    private final UserService userService;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;

    public VerificationService(VerificationTokenStore tokenStore, UserService userService,
                               TokenService tokenService, RefreshTokenService refreshTokenService,
                               JwtProperties jwtProperties) {
        this.tokenStore = tokenStore;
        this.userService = userService;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public LoginResult consume(String token, String issuedIp) {
        var userId = tokenStore.read(token).orElseThrow(VerifyTokenInvalidException::new);
        UserEntity user = userService.findById(userId).orElseThrow(VerifyTokenInvalidException::new);
        if (!user.isEmailVerified()) {
            userService.markEmailVerified(user);
        }
        // Одноразовость: токен удаляется до выдачи сессии — по той же ссылке
        // второй раз войти нельзя.
        tokenStore.delete(token);
        IssuedRefreshToken refresh = refreshTokenService.issue(user.getId(), issuedIp);
        return new LoginResult(
                tokenService.generate(user.getId(), user.getRoles()),
                jwtProperties.accessTtl().toSeconds(),
                refresh.cookieValue());
    }
}
