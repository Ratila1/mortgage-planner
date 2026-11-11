package dev.nosurprise.notification;

import dev.nosurprise.common.messaging.MailRequested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест consumer mail.send.q (§8): отправка письма через JavaMailSender;
 * идемпотентность по eventId (повторная доставка → письмо не отправляется второй раз);
 * сбой отправки снимает claim и прокидывает исключение (retry → DLQ).
 */
@ExtendWith(MockitoExtension.class)
class MailSendConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    JavaMailSender mailSender;

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> values;

    MailSendConsumer consumer;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        consumer = new MailSendConsumer(mailSender, redis, "noreply@nosurprise.dev");
    }

    private MailRequested event() {
        return new MailRequested(EVENT_ID, Instant.now(), "user@example.com",
                "Confirm your email", "Confirm your email: http://localhost:8080/api/auth/verify-email?token=token-1");
    }

    private void claimSucceeds() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        lenient().when(values.setIfAbsent("mail:processed:" + EVENT_ID, "1", MailSendConsumer.PROCESSED_TTL))
                .thenReturn(true);
    }

    @Test
    @DisplayName("первая доставка: письмо отправляется, claim записывается")
    void firstDeliverySendsMail() {
        claimSucceeds();

        consumer.on(event());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("noreply@nosurprise.dev");
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).isEqualTo("Confirm your email");
        assertThat(message.getText()).contains("/api/auth/verify-email?token=token-1");
    }

    @Test
    @DisplayName("повторная доставка того же eventId: письмо НЕ отправляется")
    void duplicateDeliverySkipped() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("mail:processed:" + EVENT_ID, "1", MailSendConsumer.PROCESSED_TTL))
                .thenReturn(false);

        consumer.on(event());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("сбой отправки: claim снимается, исключение прокидывается (для retry/DLQ)")
    void sendFailureReleasesClaimAndRethrows() {
        claimSucceeds();
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> consumer.on(event())).isInstanceOf(IllegalStateException.class);

        verify(redis).delete("mail:processed:" + EVENT_ID);
    }

    @Test
    @DisplayName("после снятия claim повторная доставка снова попробует отправить")
    void claimReleasedSoRetryCanAttemptAgain() {
        claimSucceeds();
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down"))
                .doNothing()
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> consumer.on(event())).isInstanceOf(IllegalStateException.class);
        consumer.on(event());

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        verify(values, times(2)).setIfAbsent(anyString(), anyString(), any());
    }
}
