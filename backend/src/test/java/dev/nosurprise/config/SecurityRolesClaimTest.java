package dev.nosurprise.config;

import dev.nosurprise.auth.service.JwtProperties;
import dev.nosurprise.auth.service.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityRolesClaimTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withPropertyValues(
                    "app.jwt.secret=test-secret-0123456789abcdef0123456789",
                    "app.jwt.access-ttl=15m",
                    "app.jwt.refresh-ttl=30d")
            .withUserConfiguration(SecurityConfig.class, JwtPropertiesConfig.class);

    private static MockMvc mockMvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("ADMIN JWT (roles claim) проходит /api/admin/**")
    void adminJwtAllowed() {
        runner.run(context -> {
            TokenService tokenService = new TokenService(
                    new JwtProperties("test-secret-0123456789abcdef0123456789",
                            Duration.ofMinutes(15), Duration.ofDays(30)),
                    Clock.systemUTC());
            String token = tokenService.generate(UUID.randomUUID(), Set.of("ADMIN"));
            MockMvc mvc = mockMvc(context);
            mvc.perform(get("/api/admin/offers")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        });
    }

    @Test
    @DisplayName("USER JWT (roles claim) получает 403 на /api/admin/**")
    void userJwtForbidden() {
        runner.run(context -> {
            TokenService tokenService = new TokenService(
                    new JwtProperties("test-secret-0123456789abcdef0123456789",
                            Duration.ofMinutes(15), Duration.ofDays(30)),
                    Clock.systemUTC());
            String token = tokenService.generate(UUID.randomUUID(), Set.of("USER"));
            MockMvc mvc = mockMvc(context);
            mvc.perform(get("/api/admin/offers")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        });
    }
}