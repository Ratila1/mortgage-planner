package dev.nosurprise.smsverificationservice.integration;

import dev.nosurprise.smsverificationservice.integration.support.AbstractSmsIntegrationTest;
import dev.nosurprise.smsverificationservice.service.OtpHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Создание SMS-запроса: нормализация номера, идемпотентность, конфликт ключа,
 * лимит на номер и чтение статуса.
 */
class SmsCreateIntegrationTest extends AbstractSmsIntegrationTest {

    private static final String PHONE = "+375291234567";

    @Test
    @DisplayName("create: 202, номер в E.164, код в сообщении, в БД только SHA-256")
    void createsPendingRequestWithHashedCode() {
        ResponseEntity<String> response = api.create("create-1", "+375 (29) 123-45-67");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = json(response.getBody());
        UUID id = UUID.fromString(body.get("id").asString());
        assertThat(body.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(body.get("attempts").asInt()).isZero();
        assertThat(body.get("expiresAt").isNull()).isFalse();

        Map<String, Object> row = jdbc.queryForMap(
                "select phone, language, status, attempts, otp_hash, otp_expires_at, message "
                        + "from sms.sms_request where id = cast(? as uuid)", id.toString());
        assertThat(row.get("phone")).isEqualTo(PHONE);
        assertThat(row.get("language")).isEqualTo("RU");
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat((String) row.get("otp_hash")).matches("[0-9a-f]{64}");

        String code = codeFromMessage(id);
        assertThat(row.get("otp_hash")).isEqualTo(new OtpHasher().hash(code));
        assertThat(row.get("message").toString()).contains(code);
        assertThat(row.get("otp_expires_at")).isNotNull();
    }

    @Test
    @DisplayName("повтор с тем же Idempotency-Key и телефоном: 200, та же запись, второй строки нет")
    void sameIdempotencyKeyReturnsExistingRequest() {
        ResponseEntity<String> first = api.create("create-idem", PHONE);
        ResponseEntity<String> second = api.create("create-idem", PHONE);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(second.getBody()).get("id").asString())
                .isEqualTo(json(first.getBody()).get("id").asString());
        assertThat(countRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("тот же Idempotency-Key с другим телефоном: 409")
    void sameIdempotencyKeyWithAnotherPhoneConflicts() {
        assertThat(api.create("create-conflict", PHONE).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> conflict = api.create("create-conflict", "+375291234568");

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(conflict.getBody()).get("message").asString()).contains("different phone");
    }

    @Test
    @DisplayName("без Idempotency-Key: 400")
    void missingIdempotencyKeyRejected() {
        ResponseEntity<String> response = api.create(null, PHONE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response.getBody()).get("message").asString()).contains("Idempotency-Key");
    }

    @Test
    @DisplayName("некорректный телефон: 422")
    void invalidPhoneRejected() {
        ResponseEntity<String> response = api.create("create-invalid", "not-a-phone");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(json(response.getBody()).get("message").asString()).contains("Invalid phone number");
    }

    @Test
    @DisplayName("лимит на номер: 3 запроса в минуту, четвёртый → 429")
    void rateLimitsRequestsPerPhone() {
        assertThat(api.create("rate-1", PHONE).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(api.create("rate-2", PHONE).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(api.create("rate-3", PHONE).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> limited = api.create("rate-4", PHONE);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(json(limited.getBody()).get("message").asString()).contains("rate limit");
        assertThat(countRequests()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET статуса: 200 для существующего id, 404 для неизвестного")
    void getReturnsStatusOrNotFound() {
        UUID id = UUID.fromString(json(api.create("create-get", PHONE).getBody()).get("id").asString());

        ResponseEntity<String> found = api.get(id);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(found.getBody()).get("id").asString()).isEqualTo(id.toString());

        ResponseEntity<String> missing = api.get(UUID.randomUUID());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private int countRequests() {
        Integer count = jdbc.queryForObject("select count(*) from sms.sms_request", Integer.class);
        return count == null ? 0 : count;
    }
}
