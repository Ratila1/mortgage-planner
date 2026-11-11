package dev.nosurprise.config;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Защита роутов (§7): публичные — /api/calc/**, /api/offers, /api/auth/**, /actuator/health/**;
 * /api/admin/** — ROLE_ADMIN; остальное (включая /actuator/prometheus) — аутентифицированные.
 * 401/403 — ProblemDetail с code. Реальный SecurityFilterChain через WebApplicationContextRunner
 * (без БД/Mongo/Redis/Rabbit); JWT-аутентификация — постпроцессор spring-security-test.
 * Контроллеры в срезе не зарегистрированы, поэтому «прошёл security» = 404 (нет handler-а).
 */
class SecurityConfigTest {

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
    @DisplayName("без JWT: защищённый роут → 401 ProblemDetail code=UNAUTHORIZED")
    void unauthorizedWithoutJwt() {
        runner.run(context -> {
            MockMvc mockMvc = mockMvc(context);
            mockMvc.perform(get("/api/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(Matchers.containsString("\"code\":\"UNAUTHORIZED\"")))
                    .andExpect(content().string(Matchers.containsString("\"status\":401")));
            mockMvc.perform(post("/api/scenarios")).andExpect(status().isUnauthorized());
        });
    }

    @Test
    @DisplayName("JWT с ролью USER на /api/admin/** → 403 ProblemDetail code=FORBIDDEN")
    void nonAdminForbiddenOnAdminRoutes() {
        runner.run(context -> {
            MockMvc mockMvc = mockMvc(context);
            mockMvc.perform(get("/api/admin/offers")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(Matchers.containsString("\"code\":\"FORBIDDEN\"")))
                    .andExpect(content().string(Matchers.containsString("\"status\":403")));
        });
    }

    @Test
    @DisplayName("ROLE_ADMIN проходит на /api/admin/** (security пропускает → нет контроллера → 404)")
    void adminAllowedOnAdminRoutes() {
        runner.run(context -> {
            MockMvc mockMvc = mockMvc(context);
            mockMvc.perform(get("/api/admin/offers")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isNotFound());
        });
    }

    @Test
    @DisplayName("валидный JWT открывает защищённый роут (/api/me → 404, security пройден)")
    void validJwtAllowsProtectedRoute() {
        runner.run(context -> {
            MockMvc mockMvc = mockMvc(context);
            mockMvc.perform(get("/api/me").with(jwt())).andExpect(status().isNotFound());
        });
    }

    @Test
    @DisplayName("публичные роуты без JWT: /api/calc/**, /api/offers, /api/auth/**, /actuator/health/** → 404 (не 401/403)")
    void publicRoutesAllowedWithoutJwt() {
        runner.run(context -> {
            MockMvc mockMvc = mockMvc(context);
            mockMvc.perform(post("/api/calc")).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/offers")).andExpect(status().isNotFound());
            mockMvc.perform(post("/api/auth/login")).andExpect(status().isNotFound());
            mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        });
    }

    @Test
    @DisplayName("/actuator/prometheus — НЕ публичный: без JWT → 401")
    void prometheusRequiresAuthentication() {
        runner.run(context -> {
            MockMvc mockMvc = mockMvc(context);
            mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        });
    }
}
