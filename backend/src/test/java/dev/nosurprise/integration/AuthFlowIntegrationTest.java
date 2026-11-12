package dev.nosurprise.integration;

import dev.nosurprise.integration.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth flow на реальных PostgreSQL/Redis/RabbitMQ: регистрация → подтверждение почты →
 * логин → access JWT → ротация refresh → logout → отозванный refresh.
 * Токен подтверждения берём из Redis (verify:{token}), письмо не парсим.
 */
@DisplayName("Auth flow: register → verify → login → refresh → logout")
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Strong-pass-123";
    private static final String WRONG_PASSWORD = "Wrong-pass-123";
    private static final String VERIFY_KEY_PREFIX = "verify:";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("полный цикл: регистрация, подтверждение, логин, /api/me, ротация refresh, logout")
    void fullAuthFlow() {
        String email = uniqueEmail("auth-flow");

        // 1. Регистрация: 202 + нейтральный ответ, пользователь ещё не подтверждён.
        ResponseEntity<String> registered = api.postJson("/api/auth/register", credentials(email, PASSWORD));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(message(registered.getBody()))
                .isEqualTo("If the email can be registered, a confirmation letter has been sent.");

        UUID userId = userId(email);
        assertThat(emailVerified(email)).isFalse();
        String verificationToken = verificationTokenFor(userId);

        // 2. Неподтверждённый пользователь не может войти.
        ResponseEntity<String> unverifiedLogin = api.postJson("/api/auth/login", credentials(email, PASSWORD));
        assertThat(unverifiedLogin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(unverifiedLogin)).isEqualTo("EMAIL_NOT_VERIFIED");

        // 3. Неверный пароль и неизвестный email — одинаковый 401 (email не раскрывается).
        ResponseEntity<String> wrongPassword = api.postJson("/api/auth/login",
                credentials(email, WRONG_PASSWORD));
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(wrongPassword)).isEqualTo("INVALID_CREDENTIALS");

        ResponseEntity<String> unknownEmail = api.postJson("/api/auth/login",
                credentials(uniqueEmail("unknown"), PASSWORD));
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(unknownEmail)).isEqualTo("INVALID_CREDENTIALS");

        // 4. Подтверждение почты токеном из Redis.
        ResponseEntity<String> verified = api.get("/api/auth/verify-email?token=" + verificationToken);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(message(verified.getBody())).isEqualTo("Email confirmed.");
        assertThat(emailVerified(email)).isTrue();
        assertThat(redis.hasKey(VERIFY_KEY_PREFIX + verificationToken)).isFalse();

        // 5. Логин: access JWT в теле, refresh в httpOnly-cookie.
        ResponseEntity<String> login = api.postJson("/api/auth/login", credentials(email, PASSWORD));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode loginBody = objectMapper.readTree(login.getBody());
        String accessToken = loginBody.get("accessToken").asString();
        assertThat(loginBody.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(loginBody.get("expiresIn").asLong()).isPositive();

        assertJwt(accessToken, userId);
        assertRefreshCookieAttributes(login.getHeaders());

        String firstRefresh = api.cookie("refresh_token");
        assertThat(firstRefresh).isNotNull().startsWith(userId + ".");

        // 6. Access JWT работает для /api/me; без токена — 401.
        ResponseEntity<String> me = api.get("/api/me", accessToken);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode meBody = objectMapper.readTree(me.getBody());
        assertThat(meBody.get("id").asString()).isEqualTo(userId.toString());
        assertThat(meBody.get("email").asString()).isEqualTo(email);
        assertThat(meBody.get("emailVerified").asBoolean()).isTrue();
        assertThat(meBody.get("roles").toString()).contains("USER");

        ResponseEntity<String> anonymousMe = api.get("/api/me");
        assertThat(anonymousMe.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(anonymousMe)).isEqualTo("UNAUTHORIZED");

        // 7. Refresh: ротация — новый refresh-cookie, старый больше не работает.
        ResponseEntity<String> refreshed = api.post("/api/auth/refresh");
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondRefresh = api.cookie("refresh_token");
        assertThat(secondRefresh).isNotNull().isNotEqualTo(firstRefresh);

        api.setCookie("refresh_token", firstRefresh);
        ResponseEntity<String> replay = api.post("/api/auth/refresh");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(replay)).isEqualTo("INVALID_REFRESH_TOKEN");

        // 8. Logout отзывает текущий refresh.
        api.setCookie("refresh_token", secondRefresh);
        ResponseEntity<String> logout = api.post("/api/auth/logout");
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.cookie("refresh_token")).isNull();

        api.setCookie("refresh_token", secondRefresh);
        ResponseEntity<String> afterLogout = api.post("/api/auth/refresh");
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(afterLogout)).isEqualTo("INVALID_REFRESH_TOKEN");

        // 9. Повторный logout без cookie идемпотентен.
        api.removeCookie("refresh_token");
        assertThat(api.post("/api/auth/logout").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("повторная регистрация существующего email не создаёт второго пользователя и не раскрывает факт существования")
    void duplicateRegistrationIsNeutral() {
        String email = uniqueEmail("auth-dup");

        ResponseEntity<String> first = api.postJson("/api/auth/register", credentials(email, PASSWORD));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        UUID userId = userId(email);
        assertThat(countUsers(email)).isEqualTo(1);
        assertThat(verificationTokensFor(userId)).hasSize(1);

        ResponseEntity<String> second = api.postJson("/api/auth/register", credentials(email, PASSWORD));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // Контракт §9: одинаковый ответ для нового и существующего email.
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(countUsers(email)).isEqualTo(1);
        assertThat(verificationTokensFor(userId)).hasSize(1);
    }

    private void assertJwt(String accessToken, UUID userId) {
        String[] parts = accessToken.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode claims = objectMapper.readTree(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        assertThat(claims.get("sub").asString()).isEqualTo(userId.toString());
        assertThat(claims.get("roles").toString()).contains("USER");
        assertThat(claims.get("exp").asLong())
                .as("exp в будущем")
                .isGreaterThan(Instant.now().getEpochSecond());
    }

    private void assertRefreshCookieAttributes(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull().hasSize(1);
        String cookie = setCookies.get(0);
        assertThat(cookie).contains("refresh_token=", "HttpOnly", "SameSite=Lax", "Path=/api/auth");
        // Secure добавляется только при request.isSecure(); в тестах backend работает по HTTP.
        assertThat(cookie).doesNotContain("Secure");
    }

    private String uniqueEmail(String prefix) {
        return "it-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@nosurprise.test";
    }

    private String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private String message(String body) {
        return objectMapper.readTree(body).get("message").asString();
    }

    private String code(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody()).get("code").asString();
    }

    private UUID userId(String email) {
        String id = jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
        assertThat(id).as("пользователь %s создан", email).isNotNull();
        return UUID.fromString(id);
    }

    private boolean emailVerified(String email) {
        Boolean verified = jdbc.queryForObject(
                "select email_verified from users where email = ?", Boolean.class, email);
        return Boolean.TRUE.equals(verified);
    }

    private int countUsers(String email) {
        Integer count = jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
        return count == null ? 0 : count;
    }

    private String verificationTokenFor(UUID userId) {
        Set<String> tokens = verificationTokensFor(userId);
        assertThat(tokens).as("verify-токен в Redis для %s", userId).hasSize(1);
        return tokens.iterator().next();
    }

    private Set<String> verificationTokensFor(UUID userId) {
        Set<String> keys = redis.keys(VERIFY_KEY_PREFIX + "*");
        if (keys == null) {
            return Set.of();
        }
        return keys.stream()
                .filter(key -> userId.toString().equals(redis.opsForValue().get(key)))
                .map(key -> key.substring(VERIFY_KEY_PREFIX.length()))
                .collect(Collectors.toSet());
    }
}
