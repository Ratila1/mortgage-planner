package dev.nosurprise.smsverificationservice.integration;

import dev.nosurprise.smsverificationservice.integration.support.AbstractSmsIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка OTP: NOT_READY до отправки, успех, неверный код, сгорание после 3 попыток,
 * истечение срока и защита эндпоинта сервисным JWT.
 */
class SmsVerifyIntegrationTest extends AbstractSmsIntegrationTest {

    private static final String PHONE = "+375291234567";

    @Test
    @DisplayName("код до фактической отправки: NOT_READY")
    void notReadyBeforeSmsIsSent() {
        UUID id = create();

        JsonNode body = verify(id, codeFromMessage(id), serviceToken());

        assertThat(body.get("verified").asBoolean()).isFalse();
        assertThat(body.get("reason").asString()).isEqualTo("NOT_READY");
    }

    @Test
    @DisplayName("верный код после отправки: SUCCESS, повторная проверка уже не тратит попытку")
    void verifiesCorrectCodeOnce() {
        UUID id = createAndSend();
        String code = codeFromMessage(id);

        JsonNode first = verify(id, code, serviceToken());

        assertThat(first.get("verified").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                "select verified_at is not null from sms.sms_request where id = cast(? as uuid)",
                Boolean.class, id.toString())).isTrue();

        JsonNode second = verify(id, code, serviceToken());

        assertThat(second.get("verified").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("неверный код: INVALID_CODE, попытка фиксируется в БД")
    void invalidCodeIsCounted() {
        UUID id = createAndSend();

        JsonNode body = verify(id, wrongCode(id), serviceToken());

        assertThat(body.get("verified").asBoolean()).isFalse();
        assertThat(body.get("reason").asString()).isEqualTo("INVALID_CODE");
        assertThat(verifyAttempts(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("3 неверных попытки сжигают код: дальше даже верный код → TOO_MANY_ATTEMPTS")
    void burnsCodeAfterThreeAttempts() {
        UUID id = createAndSend();
        String wrong = wrongCode(id);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(verify(id, wrong, serviceToken()).get("reason").asString()).isEqualTo("INVALID_CODE");
        }
        assertThat(verifyAttempts(id)).isEqualTo(3);

        JsonNode burned = verify(id, codeFromMessage(id), serviceToken());

        assertThat(burned.get("verified").asBoolean()).isFalse();
        assertThat(burned.get("reason").asString()).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @Test
    @DisplayName("истёкший OTP: EXPIRED, даже если код верный")
    void expiredCodeIsRejected() {
        UUID id = createAndSend();
        jdbc.update("update sms.sms_request set otp_expires_at = now() - interval '1 minute' "
                + "where id = cast(? as uuid)", id.toString());

        JsonNode body = verify(id, codeFromMessage(id), serviceToken());

        assertThat(body.get("verified").asBoolean()).isFalse();
        assertThat(body.get("reason").asString()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("неизвестный id: 404")
    void unknownIdReturnsNotFound() {
        ResponseEntity<String> response = api.verify(UUID.randomUUID(), "123456", serviceToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("без JWT: 401 — проверку кода может вызвать только backend")
    void requiresServiceJwt() {
        UUID id = createAndSend();

        ResponseEntity<String> response = api.verify(id, codeFromMessage(id), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private UUID create() {
        return UUID.fromString(json(api.create("verify-" + UUID.randomUUID(), PHONE).getBody()).get("id").asString());
    }

    private UUID createAndSend() {
        UUID id = create();
        markSent(id);
        return id;
    }

    private JsonNode verify(UUID id, String code, String token) {
        ResponseEntity<String> response = api.verify(id, code, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response.getBody());
    }

    private String wrongCode(UUID id) {
        String code = codeFromMessage(id);
        return "000000".equals(code) ? "111111" : "000000";
    }

    private int verifyAttempts(UUID id) {
        Integer attempts = jdbc.queryForObject(
                "select verify_attempts from sms.sms_request where id = cast(? as uuid)",
                Integer.class, id.toString());
        return attempts == null ? 0 : attempts;
    }
}
