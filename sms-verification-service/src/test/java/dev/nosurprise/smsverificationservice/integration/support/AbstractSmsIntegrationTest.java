package dev.nosurprise.smsverificationservice.integration.support;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * База integration-тестов sms-verification-service: полный Spring Boot context на реальных
 * PostgreSQL и Redis (Testcontainers), без моков инфраструктуры.
 * Профиль {@code sim} даёт DispatchSmsProvider; фоновые задачи отключены (PT24H),
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

    private static final Pattern OTP_IN_MESSAGE = Pattern.compile("(\\d{6})");
    private static final String SMS_BY_ID = "select message from sms.sms_request where id = cast(? as uuid)";

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
        api = new SmsApiClient(port);
        jdbc.update("delete from sms.sms_attempt");
        jdbc.update("delete from sms.sms_request");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    protected JsonNode json(String body) {
        return objectMapper.readTree(body);
    }

    /** OTP хранится в БД открытым текстом в message — по спеке это нужно для ретраев отправки. */
    protected String codeFromMessage(UUID smsId) {
        String message = jdbc.queryForObject(SMS_BY_ID, String.class, smsId.toString());
        Matcher matcher = OTP_IN_MESSAGE.matcher(message == null ? "" : message);
        assertThat(matcher.find()).as("в сообщении есть 6-значный код: %s", message).isTrue();
        return matcher.group(1);
    }

    protected void markSent(UUID smsId) {
        jdbc.update("update sms.sms_request set status = 'SUCCESS', sent_at = now() where id = cast(? as uuid)",
                smsId.toString());
    }

    /** JWT того же формата, что подписывает backend (ServiceJwt): HS256 на общем секрете. */
    protected String serviceToken() {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader(JWSAlgorithm.HS256),
                    new JWTClaimsSet.Builder()
                            .subject("sms-client")
                            .issueTime(Date.from(Instant.now()))
                            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                            .build());
            jwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign service JWT", e);
        }
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SmsTestEnvironment.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", SmsTestEnvironment.POSTGRES::getUsername);
        registry.add("spring.datasource.password", SmsTestEnvironment.POSTGRES::getPassword);

        registry.add("spring.data.redis.host", SmsTestEnvironment.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> SmsTestEnvironment.REDIS.getMappedPort(6379));

        registry.add("app.jwt.secret", () -> JWT_SECRET);
    }
}
