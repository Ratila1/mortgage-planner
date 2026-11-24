package dev.nosurprise.sms.integration.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * База integration-тестов sms-verification-service: полный Spring Boot context на реальных
 * PostgreSQL и Redis (Testcontainers), без моков инфраструктуры.
 * Профиль {@code sim} даёт SmsSender; фоновые задачи отключены (PT24H),
 * диспетчер тесты вызывают вручную.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=sim",
                "sms.dispatch-interval=PT24H",
                "sms.dispatch-initial-delay=PT24H"
        })
public abstract class AbstractSmsIntegrationTest {

    protected static final String JWT_SECRET = "sms-integration-test-secret-at-least-32-chars-long";

    private static final String SMS_CODE_KEY = "sms:otp:code:%s";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected ObjectMapper objectMapper;

    @LocalServerPort
    protected int port;

    protected SmsApiClient api;

    @BeforeEach
    void resetState() {
        api = new SmsApiClient(port, serviceToken());
        jdbc.update("delete from sms.sms_attempt");
        jdbc.update("delete from sms.sms_request");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    protected JsonNode json(String body) {
        return objectMapper.readTree(body);
    }

    /** OTP не хранится в БД; для тестов берём код из Redis-handoff (тот же TTL, что и сам OTP). */
    protected String codeFromMessage(UUID smsId) {
        String code = redis.opsForValue().get(SMS_CODE_KEY.formatted(smsId));
        assertThat(code).as("в Redis-handoff есть 6-значный код для sms %s", smsId).matches("\\d{6}");
        return code;
    }

    protected void markSent(UUID smsId) {
        jdbc.update("update sms.sms_request set status = 'DELIVERED', sent_at = now() where id = cast(? as uuid)",
                smsId.toString());
    }

    /** JWT того же формата, что подписывает backend (ServiceJwt): HS256 на общем секрете, sub=sms-client. */
    protected String serviceToken() {
        return token("sms-client");
    }

    /** Пользовательский JWT бэкенда: subject — id пользователя, а не сервиса. */
    protected String userToken() {
        return token(UUID.randomUUID().toString());
    }

    private String token(String subject) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader(JWSAlgorithm.HS256),
                    new JWTClaimsSet.Builder()
                            .subject(subject)
                            .issueTime(Date.from(Instant.now()))
                            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                            .build());
            jwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign JWT", e);
        }
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SmsTestEnvironment.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", SmsTestEnvironment.POSTGRES::getUsername);
        registry.add("spring.datasource.password", SmsTestEnvironment.POSTGRES::getPassword);

        registry.add("spring.data.redis.host", SmsTestEnvironment.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> SmsTestEnvironment.REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", SmsTestEnvironment.RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> SmsTestEnvironment.RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", SmsTestEnvironment.RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", SmsTestEnvironment.RABBIT::getAdminPassword);

        registry.add("app.jwt.secret", () -> JWT_SECRET);
    }
}
