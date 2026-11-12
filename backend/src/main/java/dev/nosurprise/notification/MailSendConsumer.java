package dev.nosurprise.notification;

import dev.nosurprise.common.messaging.MailRequested;
import dev.nosurprise.config.RabbitMailConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Consumer mail.send.q (§8): отправка письма через JavaMailSender (Mailpit локально).
 * Идемпотентность по eventId (§8): claim через Redis SET NX mail:processed:{eventId} (72 ч) —
 * повторная доставка того же eventId не отправляет письмо второй раз.
 * Claim берётся ДО отправки; при сбое отправки claim снимается и исключение прокидывается —
 * отрабатывает retry (1с/2с/4с), затем DLQ (§8).
 */
@Component
public class MailSendConsumer {

    private static final Logger log = LoggerFactory.getLogger(MailSendConsumer.class);

    static final String PROCESSED_KEY_PREFIX = "mail:processed:";
    static final Duration PROCESSED_TTL = Duration.ofHours(72);

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redis;
    private final String from;

    public MailSendConsumer(JavaMailSender mailSender, StringRedisTemplate redis,
                            @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.redis = redis;
        this.from = from;
    }

    @RabbitListener(queues = RabbitMailConfig.MAIL_SEND_Q)
    public void on(MailRequested event) {
        if (!claim(event.eventId())) {
            log.info("mail event {} already processed, skipping duplicate delivery", event.eventId());
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(event.to());
            message.setSubject(event.subject());
            message.setText(event.body());
            mailSender.send(message);
        } catch (RuntimeException ex) {
            unclaim(event.eventId());
            throw ex;
        }
    }

    private boolean claim(UUID eventId) {
        Boolean claimed = redis.opsForValue()
                .setIfAbsent(PROCESSED_KEY_PREFIX + eventId, "1", PROCESSED_TTL);
        return Boolean.TRUE.equals(claimed);
    }

    private void unclaim(UUID eventId) {
        redis.delete(PROCESSED_KEY_PREFIX + eventId);
    }
}
