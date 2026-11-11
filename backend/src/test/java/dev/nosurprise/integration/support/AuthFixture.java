package dev.nosurprise.integration.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регистрация + подтверждение почты + логин для integration-тестов.
 * Токен подтверждения берётся из Redis (verify:{token}), письмо не парсим.
 */
public final class AuthFixture {

    private static final String VERIFY_KEY_PREFIX = "verify:";

    private final ApiClient api;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AuthFixture(ApiClient api, JdbcTemplate jdbc, StringRedisTemplate redis,
                       ObjectMapper objectMapper) {
        this.api = api;
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Account registerAndVerify(String prefix, String password) {
        String email = "it-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@nosurprise.test";

        var registered = api.postJson("/api/auth/register", credentials(email, password));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        UUID userId = userId(email);
        String token = verificationToken(userId);

        var verified = api.get("/api/auth/verify-email?token=" + token);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(emailVerified(email)).isTrue();

        return new Account(userId, email, password, login(email, password));
    }

    public String login(String email, String password) {
        var login = api.postJson("/api/auth/login", credentials(email, password));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(login.getBody()).get("accessToken").asString();
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

    private String verificationToken(UUID userId) {
        Set<String> keys = redis.keys(VERIFY_KEY_PREFIX + "*");
        assertThat(keys).as("verify-ключи в Redis").isNotNull();
        return keys.stream()
                .filter(key -> userId.toString().equals(redis.opsForValue().get(key)))
                .map(key -> key.substring(VERIFY_KEY_PREFIX.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("verify-токен не найден для " + userId));
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    public record Account(UUID id, String email, String password, String accessToken) {
    }
}
