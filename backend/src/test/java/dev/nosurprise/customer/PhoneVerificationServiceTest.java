package dev.nosurprise.customer;

import dev.nosurprise.common.otp.CodeInvalidException;
import dev.nosurprise.common.otp.OtpCooldown;
import dev.nosurprise.smsclient.SmsClient;
import dev.nosurprise.smsclient.dto.SmsResponse;
import dev.nosurprise.smsclient.dto.VerifyResponse;
import dev.nosurprise.telegram.event.PhoneChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Код подтверждения привязан к номеру, на который он был отправлен. */
class PhoneVerificationServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SMS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PHONE = "+375291234567";
    private static final String CHANGED_PHONE = "+375299999999";
    private static final String CODE = "1234";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final CustomerService customers = mock(CustomerService.class);
    private final SmsClient smsClient = mock(SmsClient.class);
    private final OtpCooldown cooldown = mock(OtpCooldown.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private PhoneVerificationService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(customers.requireCompleteProfile(USER)).thenReturn(profile(PHONE));
        service = new PhoneVerificationService(customers, smsClient, cooldown, redis);
    }

    @Test
    @DisplayName("запрос кода сохраняет и smsId, и номер, на который ушла SMS")
    void requestCodeBindsPhone() {
        when(smsClient.createSms(anyString(), eq(PHONE)))
                .thenReturn(SmsResponse.builder().id(SMS_ID).build());

        service.requestCode(USER);

        verify(values).set(eq(PhoneVerificationService.SMS_ID_PREFIX + USER), eq(SMS_ID.toString()),
                any(Duration.class));
        verify(values).set(eq(PhoneVerificationService.PHONE_KEY_PREFIX + USER), eq(PHONE), any(Duration.class));
    }

    @Test
    @DisplayName("код для старого номера не подтверждает новый номер профиля")
    void confirmRejectsCodeForChangedPhone() {
        bindCode(PHONE);
        when(customers.requireCompleteProfile(USER)).thenReturn(profile(CHANGED_PHONE));

        assertThatThrownBy(() -> service.confirm(USER, CODE))
                .isInstanceOf(CodeInvalidException.class)
                .hasMessageContaining("phone has changed");

        verify(smsClient, never()).verify(any(), anyString());
        verify(customers, never()).markPhoneVerified(any(), anyString());
        verify(redis).delete(List.of(PhoneVerificationService.SMS_ID_PREFIX + USER,
                PhoneVerificationService.PHONE_KEY_PREFIX + USER));
    }

    @Test
    @DisplayName("подтверждение proceeds только для номера, на который отправлен код")
    void confirmUsesBoundPhone() {
        bindCode(PHONE);
        when(smsClient.verify(SMS_ID, CODE)).thenReturn(VerifyResponse.builder().verified(true).build());

        service.confirm(USER, CODE);

        verify(customers).markPhoneVerified(USER, PHONE);
        verify(redis).delete(List.of(PhoneVerificationService.SMS_ID_PREFIX + USER,
                PhoneVerificationService.PHONE_KEY_PREFIX + USER));
    }

    @Test
    @DisplayName("смена номера в профиле инвалидирует сохранённую привязку кода")
    void phoneChangeClearsBinding() {
        service.onPhoneChanged(new PhoneChangedEvent(USER, PHONE, CHANGED_PHONE));

        verify(redis).delete(List.of(PhoneVerificationService.SMS_ID_PREFIX + USER,
                PhoneVerificationService.PHONE_KEY_PREFIX + USER));
        verify(cooldown).release(PhoneVerificationService.COOLDOWN_KEY_PREFIX + USER);
    }

    private void bindCode(String phone) {
        when(values.get(PhoneVerificationService.SMS_ID_PREFIX + USER)).thenReturn(SMS_ID.toString());
        when(values.get(PhoneVerificationService.PHONE_KEY_PREFIX + USER)).thenReturn(phone);
    }

    private static ProfileView profile(String phone) {
        return new ProfileView(USER, "Иванов", "Иван", null, phone, false, BigDecimal.TEN,
                LocalDate.of(1990, 1, 1), Sex.MALE, "BY", "Минск", NOW, 1L);
    }
}
