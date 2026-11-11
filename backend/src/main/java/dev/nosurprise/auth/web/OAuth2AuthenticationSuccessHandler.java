package dev.nosurprise.auth.web;

import dev.nosurprise.auth.service.RefreshTokenService;
import dev.nosurprise.auth.service.TokenService;
import dev.nosurprise.user.Provider;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

/**
 * OAuth2 success handler (§7): provisioning/link пользователя, выдача access JWT (с ролями)
 * + refresh token в Redis и httpOnly cookie. Ответ — JSON, как при /api/auth/login.
 */
@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final dev.nosurprise.auth.service.JwtProperties jwtProperties;

    public OAuth2AuthenticationSuccessHandler(UserService userService, TokenService tokenService,
                                             RefreshTokenService refreshTokenService,
                                             dev.nosurprise.auth.service.JwtProperties jwtProperties) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
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
            if (email == null || email.isBlank()) {
                // GitHub может не отдавать email в /user — в этом случае вход невозможен по контракту (по email).
                throw new IllegalArgumentException("GitHub account has no public email");
            }
        } else {
            throw new IllegalArgumentException("Unsupported OAuth provider: " + registrationId);
        }

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("OAuth provider did not return email");
        }

        UserEntity user = userService.provisionOAuthUser(email, provider, providerId, providerVerified);

        var refresh = refreshTokenService.issue(user.getId(), request.getRemoteAddr());
        String access = tokenService.generate(user.getId(), user.getRoles());

        response.setStatus(200);
        response.setContentType("application/json");
        response.setHeader("Set-Cookie", AuthController.refreshCookie(refresh.cookieValue(), request));
        response.getWriter().write("{\"accessToken\":\"" + access
                + "\",\"tokenType\":\"Bearer\",\"expiresIn\":" + jwtProperties.accessTtl().toSeconds() + "}");
    }

    private static String attribute(OAuth2User principal, String name) {
        Object value = principal.getAttribute(name);
        return value == null ? null : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }
}