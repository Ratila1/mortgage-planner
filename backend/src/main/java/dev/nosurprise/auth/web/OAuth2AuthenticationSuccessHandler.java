package dev.nosurprise.auth.web;

import dev.nosurprise.auth.service.RefreshTokenService;
import dev.nosurprise.user.Provider;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

/**
 * OAuth2 success handler (§7): provisioning/link пользователя, выдача refresh token в Redis
 * и установка httpOnly-cookie, затем redirect в SPA. Access-токен фронтенд получает через
 * POST /api/auth/refresh (AuthProvider.restoreSession) после возврата в приложение.
 */
@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationSuccessHandler.class);

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final ObjectProvider<OAuth2AuthorizedClientService> authorizedClientServiceProvider;
    private final String appBaseUrl;

    public OAuth2AuthenticationSuccessHandler(UserService userService,
                                             RefreshTokenService refreshTokenService,
                                             ObjectProvider<OAuth2AuthorizedClientService> authorizedClientServiceProvider,
                                             @Value("${app.base-url}") String appBaseUrl) {
        this.userService = userService;
        this.refreshTokenService = refreshTokenService;
        this.authorizedClientServiceProvider = authorizedClientServiceProvider;
        this.appBaseUrl = normalizeBaseUrl(appBaseUrl);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
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
                    email = fetchGitHubPrimaryEmail(authentication, registrationId);
                }
            } else {
                redirectFailure(response, "failed");
                return;
            }

            if (email == null || email.isBlank()) {
                redirectFailure(response, "no_email");
                return;
            }
            if (!providerVerified) {
                redirectFailure(response, "unverified_email");
                return;
            }

            UserEntity user = userService.provisionOAuthUser(email, provider, providerId, providerVerified);
            if (user.isBlocked()) {
                redirectFailure(response, "blocked");
                return;
            }
            var refresh = refreshTokenService.issue(user.getId(), request.getRemoteAddr());
            response.setHeader("Set-Cookie", AuthController.refreshCookie(refresh.cookieValue(), request));
            response.sendRedirect(appBaseUrl + "/");
        } catch (RuntimeException ex) {
            log.warn("OAuth2 login failed", ex);
            redirectFailure(response, "failed");
        }
    }

    private String fetchGitHubPrimaryEmail(Authentication authentication, String registrationId) {
        OAuth2AuthorizedClientService authorizedClientService = authorizedClientServiceProvider.getIfAvailable();
        if (authorizedClientService == null || authentication == null) {
            return null;
        }
        OAuth2User principal = (authentication instanceof OAuth2AuthenticationToken o) ? o.getPrincipal() : null;
        try {
            OAuth2AuthorizedClient client = null;
            // getAttribute — generic <A> A: в String.valueOf(...) javac выберет valueOf(char[]) и в рантайме
            // упадёт ClassCastException "String cannot be cast to [C". Кастуем к Object явно (§ фикс GH email).
            Object login = principal == null ? null : principal.getAttribute("login");
            Object id = principal == null ? null : principal.getAttribute("id");
            for (String candidate : new String[] { authentication.getName(),
                    login == null ? null : String.valueOf(login),
                    id == null ? null : String.valueOf(id) }) {
                if (candidate == null || "null".equals(candidate) || candidate.isBlank()) continue;
                client = authorizedClientService.loadAuthorizedClient(registrationId, candidate);
                if (client != null && client.getAccessToken() != null) break;
            }
            if (client == null || client.getAccessToken() == null) {
                return null;
            }
            String token = client.getAccessToken().getTokenValue();
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/user/emails"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(resp.body());
            String fallback = null;
            for (JsonNode e : node) {
                String email = e.path("email").asText(null);
                if (email == null || email.isBlank()) {
                    continue;
                }
                boolean verified = e.path("verified").asBoolean(false);
                boolean primary = e.path("primary").asBoolean(false);
                String normalized = email.trim().toLowerCase(Locale.ROOT);
                if (primary && verified) {
                    return normalized;
                }
                if (verified && fallback == null) {
                    fallback = normalized;
                }
            }
            return fallback;
        } catch (Exception ex) {
            log.warn("Failed to fetch GitHub primary email", ex);
            return null;
        }
    }

    private void redirectFailure(HttpServletResponse response, String code) throws IOException {
        response.sendRedirect(appBaseUrl + "/login?oauthError=" + code);
    }

    private static String attribute(OAuth2User principal, String name) {
        Object value = principal.getAttribute(name);
        return value == null ? null : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8080";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}