package dev.nosurprise;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Общая база интеграционных тестов (Testcontainers): поднимает PostgreSQL, MongoDB,
 * Redis, RabbitMQ и SMTP-заглушку GreenMail один раз на JVM, передаёт свойства
 * приложению через @DynamicPropertySource. HTTP-вызовы — обычный java.net.http.HttpClient
 * на случайный порт приложения (без TestRestTemplate-магии с обработкой ошибок).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    protected static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    protected static final String JWT_SECRET = "it-secret-0123456789-0123456789-0123456789abcdef";
    protected static final String ADMIN_EMAIL = "it-admin@example.com";
    protected static final String ADMIN_PASSWORD = "admin-password-123";
    protected static final String TEST_PASSWORD = "it-password-123";

    protected static final GreenMail SMTP;
    protected static final int SMTP_PORT;

    private static final Pattern TOKEN_IN_BODY = Pattern.compile("token=([A-Za-z0-9\\-_]+)");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static {
        POSTGRES.start();
        MONGO.start();
        REDIS.start();
        RABBIT.start();
        SMTP_PORT = reserveFreePort();
        SMTP = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", "smtp"));
        SMTP.start();
    }

    private static int reserveFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to reserve a free port for SMTP stub", ex);
        }
    }

    /** Регистрация свойств приложения на инфраструктуре контейнеров. Подклассы могут добавлять свои. */
    protected static void baseProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> SMTP_PORT);
        registry.add("app.jwt.secret", () -> JWT_SECRET);
        registry.add("APP_ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("APP_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
    }

    @Autowired
    protected ObjectMapper objectMapper;

    @LocalServerPort
    protected int port;

    /** Результат HTTP-вызова: статус, тело и заголовки ответа (ключи в нижнем регистре). */
    public record HttpResult(int status, String body, Map<String, List<String>> headers) {

        public String firstSetCookie() {
            return headers.getOrDefault("set-cookie", List.of()).stream().findFirst().orElse(null);
        }
    }

    protected HttpResult call(String method, String path, String jsonBody, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        try {
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body(), response.headers().map());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("http call interrupted: " + method + " " + path, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("http call failed: " + method + " " + path, ex);
        }
    }

    protected HttpResult get(String path) {
        return call("GET", path, null, Map.of());
    }

    protected HttpResult get(String path, String bearerToken) {
        return call("GET", path, null, Map.of("Authorization", "Bearer " + bearerToken));
    }

    protected HttpResult post(String path, String json) {
        return call("POST", path, json, Map.of());
    }

    protected HttpResult post(String path, String json, String bearerToken) {
        return call("POST", path, json, Map.of("Authorization", "Bearer " + bearerToken));
    }

    protected HttpResult put(String path, String json, String bearerToken) {
        return call("PUT", path, json, Map.of("Authorization", "Bearer " + bearerToken));
    }

    /** Логин (§9): 200, access-токен в теле, Bearer. */
    protected HttpResult login(String email, String password) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", email).put("password", password);
        HttpResult result = post("/api/auth/login", body.toString());
        assertThat(result.status()).as("login status, body=" + result.body()).isEqualTo(200);
        return result;
    }

    protected String accessTokenFrom(HttpResult login) {
        JsonNode node = objectMapper.readTree(login.body());
        assertThat(node.has("accessToken")).as("login response: " + login.body()).isTrue();
        assertThat(node.get("accessToken").asString()).isNotBlank();
        assertThat(node.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(node.get("expiresIn").asLong()).isPositive();
        return node.get("accessToken").asString();
    }

    /** Полный auth-флоу пользователя: регистрация → подтверждение из реального письма → логин. */
    protected User registerVerifiedUser(String email, String password) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", email).put("password", password);
        HttpResult registered = post("/api/auth/register", body.toString());
        assertThat(registered.status())
                .as("register status, body=" + registered.body())
                .isEqualTo(202);

        String token = awaitVerificationToken(email);
        HttpResult verified = get("/api/auth/verify-email?token=" + token);
        assertThat(verified.status())
                .as("verify-email status, body=" + verified.body())
                .isEqualTo(200);

        return new User(email, password, accessTokenFrom(login(email, password)));
    }

    protected record User(String email, String password, String accessToken) {
    }

    /** Ждёт подтверждение-письмо в GreenMail и извлекает token из ссылки (§9). */
    protected static String awaitVerificationToken(String email) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(confirmationEmailBody(email))
                        .as("confirmation email for " + email)
                        .isNotNull());
        String body = confirmationEmailBody(email);
        Matcher matcher = TOKEN_IN_BODY.matcher(body);
        assertThat(matcher.find()).as("verification token in email body: " + body).isTrue();
        return matcher.group(1);
    }

    private static String confirmationEmailBody(String email) {
        return messagesFor(email).stream()
                .map(GreenMailUtil::getBody)
                .filter(body -> body != null && body.contains("verify-email"))
                .findFirst()
                .orElse(null);
    }

    /** Письма, реально доставленные SMTP-заглушкой, адресованные данному email. */
    protected static List<MimeMessage> messagesFor(String email) {
        return Arrays.stream(SMTP.getReceivedMessages())
                .filter(message -> addressedTo(message, email))
                .toList();
    }

    private static boolean addressedTo(MimeMessage message, String email) {
        try {
            Address[] recipients = message.getAllRecipients();
            return recipients != null && Arrays.stream(recipients)
                    .anyMatch(address -> address.toString().toLowerCase(Locale.ROOT).contains(email));
        } catch (MessagingException ex) {
            return false;
        }
    }

    /** Тело письма, которое реально доставлено SMTP-заглушкой (используется в assert-ах flow-тестов). */
    protected static String awaitEmailTo(String email, String bodyPart) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(messagesFor(email).stream()
                        .map(GreenMailUtil::getBody)
                        .filter(Objects::nonNull)
                        .anyMatch(body -> body.contains(bodyPart)))
                        .as("email to " + email + " containing " + bodyPart)
                        .isTrue());
        return messagesFor(email).stream()
                .map(GreenMailUtil::getBody)
                .filter(body -> body != null && body.contains(bodyPart))
                .findFirst()
                .orElseThrow();
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
