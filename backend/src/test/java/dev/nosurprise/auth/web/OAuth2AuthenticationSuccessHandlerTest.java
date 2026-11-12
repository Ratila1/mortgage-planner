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
}