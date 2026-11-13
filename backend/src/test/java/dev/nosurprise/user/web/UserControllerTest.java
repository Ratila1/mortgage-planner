package dev.nosurprise.user.web;

import dev.nosurprise.auth.service.JwtProperties;
import dev.nosurprise.auth.service.TokenService;
import dev.nosurprise.config.JwtPropertiesConfig;
import dev.nosurprise.config.SecurityConfig;
import dev.nosurprise.user.Provider;
import dev.nosurprise.user.Role;
import dev.nosurprise.user.UserEntity;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/me (§9): доступ только с валидным JWT (без него — 401 UNAUTHORIZED,
 * не-ADMIN с JWT проходит, т.к. роут требует лишь authenticated). Внутренние поля
 * (password_hash, provider_id) наружу не отдаются. Security — реальная цепочка
 * SecurityConfig (JWT resource server), токен генерирует TokenService тем же секретом.
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtPropertiesConfig.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-0123456789abcdef0123456789",
        "app.jwt.access-ttl=15m",
        "app.jwt.refresh-ttl=30d"
})
class UserControllerTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789";
    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;

    private static TokenService tokenService;

    @BeforeAll
    static void initTokenService() {
        tokenService = new TokenService(
                new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(30)),
                java.time.Clock.systemUTC());
    }

    private static UserEntity user(Set<Role> roles) {
        return new UserEntity(USER_ID, "user@example.com", "$2a$hash", true,
                Provider.LOCAL, null, Instant.EPOCH, roles);
    }

    @Test
    @DisplayName("GET /api/me с валидным JWT → id, email, emailVerified, roles; без внутренних полей")
    void meWithValidJwt() throws Exception {
        when(userService.findById(USER_ID)).thenReturn(Optional.of(user(Set.of(Role.USER))));

        mockMvc.perform(get("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.generate(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.providerId").doesNotExist())
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("GET /api/me без JWT → 401 ProblemDetail code=UNAUTHORIZED")
    void meWithoutJwtUnauthorized() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/me с невалидным (подделанным) JWT → 401 UNAUTHORIZED")
    void meWithTamperedJwtUnauthorized() throws Exception {
        mockMvc.perform(get("/api/me")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + tokenService.generate(USER_ID) + "x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/me: JWT валиден, но пользователя нет (subject удалён) → 401 UNAUTHORIZED")
    void meWhenUserMissing() throws Exception {
        when(userService.findById(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.generate(USER_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/me: роль USER (не ADMIN) проходит — роут требует только authenticated")
    void meAllowsPlainUser() throws Exception {
        when(userService.findById(USER_ID)).thenReturn(Optional.of(user(Set.of(Role.USER))));

        mockMvc.perform(get("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.generate(USER_ID)))
                .andExpect(status().isOk());
    }
}
