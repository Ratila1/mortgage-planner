package dev.nosurprise.auth.web;

import dev.nosurprise.auth.service.RefreshTokenService;
import dev.nosurprise.auth.service.TokenService;
import dev.nosurprise.user.Provider;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

/**
 * OAuth2 success handler (§7): provisioning/link пользователя, выдача access JWT (с ролями)
 * + refresh token в Redis и httpOnly cookie, затем редирект на фронт ("/"):
 * access-токен фронт получает сам через POST /api/auth/refresh.
 */
@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    /** Страница входа фронта; код ошибки фронт показывает человеческим текстом. */
    public static final String LOGIN_ERROR_URL = "/login?oauthError=";

    private final UserService userService;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final dev.nosurprise.auth.service.JwtProperties jwtProperties;
    private final GitHubEmailClient gitHubEmailClient;

    public OAuth2AuthenticationSuccessHandler(UserService userService, TokenService tokenService,
                                             RefreshTokenService refreshTokenService,
                                             dev.nosurprise.auth.service.JwtProperties jwtProperties) {
        this(userService, tokenService, refreshTokenService, jwtProperties, null);
    }

    @Autowired
    public OAuth2AuthenticationSuccessHandler(UserService userService, TokenService tokenService,
                                             RefreshTokenService refreshTokenService,
                                             dev.nosurprise.auth.service.JwtProperties jwtProperties,
                                             GitHubEmailClient gitHubEmailClient) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
        this.gitHubEmailClient = gitHubEmailClient;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
        OAuth2User principal = oauth.getPrincipal();
        String registrationId = oauth.getAuthorizedClientRegistrationId();

        String email;
        String providerId;
        boolean providerVerified;
        Provider provider;
        if ("google".equalsIgnoreCase(registrationId)) {
            provider = Provider.GOOGLE;
            email = attribute(principal, "email");
            providerId = attribute(principal, "sub");
            providerVerified = Boolean.TRUE.equals(principal.getAttribute("email_verified"));
        } else if ("github".equalsIgnoreCase(registrationId)) {
            provider = Provider.GITHUB;
            email = attribute(principal, "email");
            Object id = principal.getAttribute("id");
            providerId = (id == null) ? null : String.valueOf(id);
            providerVerified = true;
            if ((email == null || email.isBlank()) && gitHubEmailClient != null) {
                // Почта скрыта в профиле GitHub — берём основной подтверждённый адрес из /user/emails.
                String fromApi = gitHubEmailClient.findPrimaryVerifiedEmail(oauth);
                email = fromApi == null ? null : fromApi.trim().toLowerCase(Locale.ROOT);
            }
        } else {
            response.sendRedirect(LOGIN_ERROR_URL + "unsupported_provider");
            return;
        }

        if (email == null || email.isBlank()) {
            // Пользователя нельзя завести без email: возвращаем на страницу входа с понятной ошибкой.
            response.sendRedirect(LOGIN_ERROR_URL + "no_email");
            return;
        }

        UserEntity user = userService.provisionOAuthUser(email, provider, providerId, providerVerified);

        var refresh = refreshTokenService.issue(user.getId(), request.getRemoteAddr());

        // Браузер пришёл сюда с редиректа провайдера, JSON ему показать некуда.
        // Ставим refresh-cookie и возвращаем на фронт: при загрузке он сам вызовет
        // POST /api/auth/refresh и получит access-токен.
        response.setHeader("Set-Cookie", AuthController.refreshCookie(refresh.cookieValue(), request));
        // Сессия нужна была только на время OAuth-обмена; дальше фронт работает по JWT.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendRedirect("/");
    }

    private static String attribute(OAuth2User principal, String name) {
        Object value = principal.getAttribute(name);
        return value == null ? null : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }
}