package dev.nosurprise.auth.web;

import dev.nosurprise.user.Provider;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2AuthenticationSuccessHandlerTest {

    @Test
    @DisplayName("Google OAuth: provisioning, JWT, refresh cookie")
    void googleSuccess() throws Exception {
        UserService userService = Mockito.mock(UserService.class);
        dev.nosurprise.auth.service.TokenService tokenService = Mockito.mock(dev.nosurprise.auth.service.TokenService.class);
        dev.nosurprise.auth.service.RefreshTokenService refreshTokenService = Mockito.mock(dev.nosurprise.auth.service.RefreshTokenService.class);
        dev.nosurprise.auth.service.JwtProperties jwtProperties =
                new dev.nosurprise.auth.service.JwtProperties("test-secret-0123456789abcdef0123456789",
                        java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(30));

        UserEntity user = new UserEntity(UUID.randomUUID(), "user@example.com", null, true,
                Provider.GOOGLE, "google-1", java.time.Instant.EPOCH, Set.of(dev.nosurprise.user.Role.USER));
        when(userService.provisionOAuthUser(eq("user@example.com"), eq(Provider.GOOGLE), eq("google-1"), eq(true)))
                .thenReturn(user);
        when(tokenService.generate(eq(user.getId()), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn("access-jwt");
        when(refreshTokenService.issue(eq(user.getId()), any()))
                .thenReturn(new dev.nosurprise.auth.service.IssuedRefreshToken(user.getId(), "opaque",
                        user.getId() + ".opaque", java.time.Instant.EPOCH));

        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(
                userService, tokenService, refreshTokenService, jwtProperties);

        OAuth2User principal = Mockito.mock(OAuth2User.class);
        when(principal.getAttribute("email")).thenReturn("user@example.com");
        when(principal.getAttribute("sub")).thenReturn("google-1");
        when(principal.getAttribute("email_verified")).thenReturn(Boolean.TRUE);

        OAuth2AuthenticationToken auth = Mockito.mock(OAuth2AuthenticationToken.class);
        when(auth.getPrincipal()).thenReturn(principal);
        when(auth.getAuthorizedClientRegistrationId()).thenReturn("google");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly");
        assertThat(response.getHeader("Set-Cookie")).contains("Path=/api/auth");
    }

    @Test
    @DisplayName("GitHub со скрытой почтой: email берётся из /user/emails")
    void gitHubHiddenEmailUsesEmailsApi() throws Exception {
        UserService userService = Mockito.mock(UserService.class);
        dev.nosurprise.auth.service.RefreshTokenService refreshTokenService = Mockito.mock(dev.nosurprise.auth.service.RefreshTokenService.class);
        GitHubEmailClient gitHubEmailClient = Mockito.mock(GitHubEmailClient.class);
        OAuth2AuthenticationToken auth = gitHubAuth();
        when(gitHubEmailClient.findPrimaryVerifiedEmail(auth)).thenReturn("Dev@Example.com");

        UserEntity user = new UserEntity(UUID.randomUUID(), "dev@example.com", null, true,
                Provider.GITHUB, "42", java.time.Instant.EPOCH, Set.of(dev.nosurprise.user.Role.USER));
        when(userService.provisionOAuthUser(eq("dev@example.com"), eq(Provider.GITHUB), eq("42"), eq(true)))
                .thenReturn(user);
        when(refreshTokenService.issue(eq(user.getId()), any()))
                .thenReturn(new dev.nosurprise.auth.service.IssuedRefreshToken(user.getId(), "opaque",
                        user.getId() + ".opaque", java.time.Instant.EPOCH));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler(userService, refreshTokenService, gitHubEmailClient).onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly");
        assertThat(request.getSession(false)).as("OAuth-сессия больше не нужна").isNull();
    }

    @Test
    @DisplayName("GitHub без доступной почты: назад на /login с кодом no_email, пользователь не создаётся")
    void gitHubWithoutEmailRedirectsToLogin() throws Exception {
        UserService userService = Mockito.mock(UserService.class);
        dev.nosurprise.auth.service.RefreshTokenService refreshTokenService = Mockito.mock(dev.nosurprise.auth.service.RefreshTokenService.class);
        GitHubEmailClient gitHubEmailClient = Mockito.mock(GitHubEmailClient.class);
        OAuth2AuthenticationToken auth = gitHubAuth();

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler(userService, refreshTokenService, gitHubEmailClient)
                .onAuthenticationSuccess(new MockHttpServletRequest(), response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?oauthError=no_email");
        assertThat(response.getHeader("Set-Cookie")).isNull();
        verify(userService, never()).provisionOAuthUser(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private static OAuth2AuthenticationSuccessHandler handler(UserService userService,
                                                             dev.nosurprise.auth.service.RefreshTokenService refreshTokenService,
                                                             GitHubEmailClient gitHubEmailClient) {
        return new OAuth2AuthenticationSuccessHandler(userService,
                Mockito.mock(dev.nosurprise.auth.service.TokenService.class), refreshTokenService,
                new dev.nosurprise.auth.service.JwtProperties("test-secret-0123456789abcdef0123456789",
                        java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(30)),
                gitHubEmailClient);
    }

    private static OAuth2AuthenticationToken gitHubAuth() {
        OAuth2User principal = Mockito.mock(OAuth2User.class);
        when(principal.getAttribute("email")).thenReturn(null);
        when(principal.getAttribute("id")).thenReturn(42);
        OAuth2AuthenticationToken auth = Mockito.mock(OAuth2AuthenticationToken.class);
        when(auth.getPrincipal()).thenReturn(principal);
        when(auth.getAuthorizedClientRegistrationId()).thenReturn("github");
        return auth;
    }
}
