package dev.nosurprise.integration;

import dev.nosurprise.integration.support.AbstractIntegrationTest;
import dev.nosurprise.integration.support.AuthFixture;
import dev.nosurprise.smsclient.SmsClient;
import dev.nosurprise.smsclient.SmsClientException;
import dev.nosurprise.smsclient.dto.SmsResponse;
import dev.nosurprise.smsclient.dto.VerifyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Повторная отправка кода подтверждения телефона (MICROLOANS.md §3.10, §4.6):
 * пауза 60 с, новый Idempotency-Key на каждый запрос, сгоревший код можно заменить новым.
 * SMS-сервис замокан: тест про поведение backend, а не про интеграцию с провайдером.
 */
class PhoneVerificationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Strong-pass-123";
    private static final String PHONE_VERIFICATION = "/api/me/phone/verification";
    private static final String PHONE_CONFIRM = "/api/me/phone/verification/confirm";
    private static final int MAX_VERIFY_ATTEMPTS = 3;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SmsClient smsClient;

    private final Map<UUID, String> codeBySmsId = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> attemptsBySmsId = new ConcurrentHashMap<>();

    private AuthFixture.Account account;
    private String phone;

    @BeforeEach
    void setUp() {
        AuthFixture auth = new AuthFixture(api, jdbc, redis, objectMapper);
        account = auth.registerAndVerify("phone", PASSWORD);
        phone = "+37529" + String.format("%07d", Math.abs(account.id().hashCode()) % 10_000_000);

        ResponseEntity<String> profile = api.putJson("/api/me/profile", profileJson(phone), account.accessToken());
        assertThat(profile.getStatusCode()).as(profile.getBody()).isEqualTo(HttpStatus.OK);

        Mockito.when(smsClient.createSms(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    UUID smsId = UUID.randomUUID();
                    codeBySmsId.put(smsId, String.format("%06d", 100000 + (int) (Math.random() * 900000)));
                    return SmsResponse.builder().id(smsId).status("IN_PROGRESS").attempts(0)
                            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
                });
        Mockito.when(smsClient.verify(ArgumentMatchers.any(UUID.class), ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    UUID smsId = inv.getArgument(0);
                    String code = inv.getArgument(1);
                    int used = attemptsBySmsId.getOrDefault(smsId, 0);
                    if (used >= MAX_VERIFY_ATTEMPTS) {
                        return VerifyResponse.builder().verified(false).reason("TOO_MANY_ATTEMPTS").build();
                    }
                    attemptsBySmsId.put(smsId, used + 1);
                    boolean ok = codeBySmsId.getOrDefault(smsId, "").equals(code);
                    return VerifyResponse.builder().verified(ok).reason(ok ? null : "INVALID_CODE").build();
                });
    }

    @Test
    @DisplayName("повторный запрос раньше 60 с → 429 RATE_LIMITED, вторая SMS не отправляется")
    void secondRequestWithinCooldownIsRejected() {
        assertThat(requestCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> second = requestCodeRaw();

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(json(second.getBody()).get("code").asString()).isEqualTo("RATE_LIMITED");
        verify(smsClient, times(1)).createSms(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("неудачная отправка не включает паузу: можно повторить сразу")
    void failedDeliveryDoesNotBlockRetry() {
        Mockito.when(smsClient.createSms(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenThrow(new SmsClientException("sms service is down"));

        ResponseEntity<String> failed = requestCodeRaw();
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(json(failed.getBody()).get("code").asString()).isEqualTo("SMS_DELIVERY_FAILED");

        Mockito.doAnswer(inv -> SmsResponse.builder().id(UUID.randomUUID()).status("IN_PROGRESS").attempts(0)
                        .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build())
                .when(smsClient).createSms(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());

        assertThat(requestCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("сгоревший код заменяется новым: пауза 60 с, потом новая SMS и новый код")
    void burnedCodeCanBeReplaced() {
        assertThat(requestCode()).isEqualTo(HttpStatus.ACCEPTED);
        String firstCode = codeBySmsId.get(singleSmsId());
        assertThat(firstCode).isNotNull();

        for (int attempt = 1; attempt <= MAX_VERIFY_ATTEMPTS; attempt++) {
            ResponseEntity<String> wrong = confirm("000000".equals(firstCode) ? "111111" : "000000");
            assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(wrong.getBody()).get("code").asString()).isEqualTo("OTP_INVALID");
        }
        ResponseEntity<String> burned = confirm("000000".equals(firstCode) ? "111111" : "000000");
        assertThat(burned.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(burned.getBody()).get("detail").asString()).contains("burned");

        // Внутри 60 секунд новую SMS не отправить — это и есть продуктовое правило.
        assertThat(requestCodeRaw().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Через минуту (в тесте снимаем ключ вручную) запрос создаёт новую SMS с новым ключом идемпотентности.
        redis.delete("phone-otp-cooldown:" + account.id());
        assertThat(requestCode()).isEqualTo(HttpStatus.ACCEPTED);

        List<String> keys = capturedIdempotencyKeys();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));

        String secondCode = codeBySmsId.get(singleSmsId());
        assertThat(confirm(secondCode).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode profile = json(api.get("/api/me/profile", account.accessToken()).getBody());
        assertThat(profile.get("phoneVerified").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("смена номера инвалидирует ожидающий код и снимает серверную паузу SMS")
    void phoneChangeInvalidatesPendingCodeAndCooldown() {
        assertThat(requestCode()).isEqualTo(HttpStatus.ACCEPTED);
        String oldCode = codeBySmsId.get(singleSmsId());
        assertThat(oldCode).isNotNull();

        String changedPhone = changedPhone(phone);
        assertThat(api.putJson("/api/me/profile", profileJson(changedPhone), account.accessToken())
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(redis.opsForValue().get("phone-otp-cooldown:" + account.id())).isNull();
        assertThat(redis.opsForValue().get("phone-sms-id:" + account.id())).isNull();
        assertThat(redis.opsForValue().get("phone-code-phone:" + account.id())).isNull();

        ResponseEntity<String> response = confirm(oldCode);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response.getBody()).get("code").asString()).isEqualTo("OTP_INVALID");

        JsonNode profile = json(api.get("/api/me/profile", account.accessToken()).getBody());
        assertThat(profile.get("phone").asString()).isEqualTo(changedPhone);
        assertThat(profile.get("phoneVerified").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("код для старого номера отклоняется confirm даже без события смены профиля")
    void codeForOldPhoneIsRejectedWithoutProfileEvent() {
        assertThat(requestCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID smsId = singleSmsId();
        String oldCode = codeBySmsId.get(smsId);
        assertThat(oldCode).isNotNull();

        String changedPhone = changedPhone(phone);
        jdbc.update("update customer_profile set phone = ?, phone_verified_at = null where user_id = ?::uuid",
                changedPhone, account.id());

        ResponseEntity<String> response = confirm(oldCode);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = json(response.getBody());
        assertThat(error.get("code").asString()).isEqualTo("OTP_INVALID");
        assertThat(error.get("detail").asString()).contains("phone has changed");
        verify(smsClient, never()).verify(eq(smsId), eq(oldCode));
        assertThat(redis.opsForValue().get("phone-sms-id:" + account.id())).isNull();
        assertThat(redis.opsForValue().get("phone-code-phone:" + account.id())).isNull();
    }

    private ResponseEntity<String> requestCodeRaw() {
        return api.postEmpty(PHONE_VERIFICATION, account.accessToken());
    }

    private HttpStatus requestCode() {
        return HttpStatus.valueOf(requestCodeRaw().getStatusCode().value());
    }

    private ResponseEntity<String> confirm(String code) {
        return api.postJson(PHONE_CONFIRM, "{\"code\":\"" + code + "\"}", account.accessToken());
    }

    private List<String> capturedIdempotencyKeys() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(smsClient, Mockito.atLeastOnce()).createSms(keys.capture(), ArgumentMatchers.anyString());
        return keys.getAllValues();
    }

    /** Последний smsId, который backend сохранил в Redis (привязка «пользователь → SMS»). */
    private UUID singleSmsId() {
        String value = redis.opsForValue().get("phone-sms-id:" + account.id());
        return value == null ? null : UUID.fromString(value);
    }

    private JsonNode json(String body) {
        return objectMapper.readTree(body);
    }

    private static String changedPhone(String phone) {
        return phone.substring(0, phone.length() - 1) + (phone.endsWith("9") ? "8" : "9");
    }

    private static String profileJson(String phone) {
        return "{\"lastName\":\"Test\",\"firstName\":\"Postman\",\"middleName\":\"User\",\"phone\":\"" + phone
                + "\",\"monthlyIncome\":\"1500.00\",\"birthDate\":\"1990-05-10\",\"sex\":\"MALE\","
                + "\"country\":\"BY\",\"city\":\"Минск\"}";
    }
}
