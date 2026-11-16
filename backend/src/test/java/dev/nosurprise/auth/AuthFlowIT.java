package dev.nosurprise.auth;

import dev.nosurprise.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * К18: auth-флоу сквозь реальный PostgreSQL + Redis + RabbitMQ + SMTP (GreenMail):
 * регистрация → подтверждение почты токеном из реально отправленного письма → логин
 * (access + refresh-cookie) → доступ к защищённому GET /api/me. Негативные ветки:
 * неверный токен подтверждения, логин до подтверждения, неверный пароль,
 * защищённый эндпоинт без токена → 401.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthFlowIT extends IntegrationTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        baseProps(registry);
    }

    @Test
    @DisplayName("регистрация → подтверждение из письма → логин → GET /api/me с access-токеном")
    void register_confirmFromRealEmail_login_meWithAccessToken() {
        String email = uniqueEmail("auth-flow");

        // регистрация → письмо реально уходит в SMTP
        HttpResult registered = post("/api/auth/register", objectNode()
                .put("email", email).put("password", TEST_PASSWORD).toString());
        assertThat(registered.status()).as("register: " + registered.body()).isEqualTo(202);

        String token = awaitVerificationToken(email);
        assertThat(token).isNotBlank();

        // подтверждение почты токеном из письма
        HttpResult verified = get("/api/auth/verify-email?token=" + token);
        assertThat(verified.status()).as("verify: " + verified.body()).isEqualTo(200);
        assertThat(objectMapper.readTree(verified.body()).get("message").asString())
                .isEqualTo("Email confirmed.");

        // логин: access в теле, refresh в httpOnly-cookie
        HttpResult login = login(email, TEST_PASSWORD);
        String accessToken = accessTokenFrom(login);
        assertThat(login.firstSetCookie()).isNotNull();
        assertThat(login.firstSetCookie()).contains("refresh_token=");
        assertThat(login.firstSetCookie()).contains("HttpOnly");

        // защищённый эндпоинт с access-токеном
        HttpResult me = get("/api/me", accessToken);
        assertThat(me.status()).as("me: " + me.body()).isEqualTo(200);
        JsonNode profile = objectMapper.readTree(me.body());
        assertThat(profile.get("email").asString()).isEqualTo(email);
        assertThat(profile.get("emailVerified").booleanValue()).isTrue();
        assertThat(profile.get("roles").toString()).contains("USER");
    }

    @Test
    @DisplayName("негатив: логин до подтверждения почты → 403 EMAIL_NOT_VERIFIED")
    void login_beforeEmailVerified_returns403EmailNotVerified() {
        String email = uniqueEmail("unverified");

        HttpResult registered = post("/api/auth/register", objectNode()
                .put("email", email).put("password", TEST_PASSWORD).toString());
        assertThat(registered.status()).isEqualTo(202);

        HttpResult login = post("/api/auth/login", objectNode()
                .put("email", email).put("password", TEST_PASSWORD).toString());
        assertThat(login.status()).isEqualTo(403);
        assertThat(objectMapper.readTree(login.body()).get("code").asString())
                .isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    @DisplayName("негатив: подтверждение несуществующим токеном → 400 VERIFY_TOKEN_INVALID")
    void verifyEmail_withUnknownToken_returns400VerifyTokenInvalid() {
        String bogus = UUID.randomUUID() + "-not-a-real-token";
        HttpResult result = get("/api/auth/verify-email?token=" + bogus);
        assertThat(result.status()).isEqualTo(400);
        assertThat(objectMapper.readTree(result.body()).get("code").asString())
                .isEqualTo("VERIFY_TOKEN_INVALID");
    }

    @Test
    @DisplayName("негатив: логин с неверным паролем → 401 INVALID_CREDENTIALS")
    void login_withWrongPassword_returns401InvalidCredentials() {
        String email = uniqueEmail("wrong-pass");
        registerVerifiedUser(email, TEST_PASSWORD);

        HttpResult login = post("/api/auth/login", objectNode()
                .put("email", email).put("password", "totally-wrong-password").toString());
        assertThat(login.status()).isEqualTo(401);
        assertThat(objectMapper.readTree(login.body()).get("code").asString())
                .isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("негатив: GET /api/me без токена → 401 UNAUTHORIZED")
    void me_withoutAccessToken_returns401Unauthorized() {
        HttpResult result = get("/api/me");
        assertThat(result.status()).isEqualTo(401);
        assertThat(objectMapper.readTree(result.body()).get("code").asString())
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("refresh ротирует cookie: старый refresh после ротации → 401 INVALID_REFRESH_TOKEN")
    void refresh_rotatesCookie_oldRefreshTokenBecomesInvalid() {
        String email = uniqueEmail("refresh");
        registerVerifiedUser(email, TEST_PASSWORD);

        HttpResult login = login(email, TEST_PASSWORD);
        String oldCookie = login.firstSetCookie();
        assertThat(oldCookie).isNotNull();
        String cookieValue = oldCookie.split(";", 2)[0];

        HttpResult refreshed = call("POST", "/api/auth/refresh", null, Map.of("Cookie", cookieValue));
        assertThat(refreshed.status()).as("refresh: " + refreshed.body()).isEqualTo(200);
        assertThat(objectMapper.readTree(refreshed.body()).get("accessToken").asString()).isNotBlank();

        HttpResult reusedOld = call("POST", "/api/auth/refresh", null, Map.of("Cookie", cookieValue));
        assertThat(reusedOld.status()).as("old cookie must be revoked: " + reusedOld.body()).isEqualTo(401);
        assertThat(objectMapper.readTree(reusedOld.body()).get("code").asString())
                .isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    @DisplayName("повторная регистрация существующего email → 202 generic, второго письма нет")
    void register_existingEmail_genericResponse_noSecondConfirmationLetter() {
        String email = uniqueEmail("dup");
        registerVerifiedUser(email, TEST_PASSWORD);

        int lettersBefore = messagesFor(email).size();

        HttpResult second = post("/api/auth/register", objectNode()
                .put("email", email).put("password", TEST_PASSWORD).toString());
        assertThat(second.status()).isEqualTo(202);
        assertThat(objectMapper.readTree(second.body()).get("message").asString())
                .isEqualTo("If the email can be registered, a confirmation letter has been sent.");

        await().atMost(Duration.ofSeconds(3)).during(Duration.ofSeconds(2)).until(() ->
                messagesFor(email).size() == lettersBefore);
    }

    private ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }
}
