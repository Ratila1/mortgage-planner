package com.bank.mortgage.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;

@Service
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final String fromEmail;
    private final String baseUrl;

    public EmailServiceImpl(JavaMailSender mailSender,
                            @Value("${spring.mail.username:}") String fromEmail,
                            @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail.isEmpty() ? "noreply@mortgage.local" : fromEmail;
        this.baseUrl = baseUrl;
    }

    @Override
    public Mono<Void> sendVerificationEmail(String email, String name, String token) {
        String link = baseUrl + "/api/auth/confirm?token=" + token;
        String subject = "Подтверждение email — Ипотека без сюрпризов";
        String html = """
                <html>
                <body>
                    <h2>Добро пожаловать, %s!</h2>
                    <p>Для подтверждения вашего email перейдите по ссылке:</p>
                    <p><a href="%s">%s</a></p>
                    <p>Ссылка действительна 24 часа.</p>
                    <p>Если вы не регистрировались, просто проигнорируйте это письмо.</p>
                </body>
                </html>
                """.formatted(name, link, link);

        return sendEmail(email, subject, html);
    }

    @Override
    public Mono<Void> sendOtpEmail(String email, String code, OtpCode.OtpPurpose purpose) {
        String subject = switch (purpose) {
            case LOGIN -> "Код для входа — Ипотека без сюрпризов";
            case REGISTER_CONFIRM -> "Код подтверждения регистрации — Ипотека без сюрпризов";
            case EMAIL_CHANGE -> "Код для смены email — Ипотека без сюрпризов";
        };

        String html = """
                <html>
                <body>
                    <h2>Ваш код: %s</h2>
                    <p>Код действителен 10 минут.</p>
                    <p>Если вы не запрашивали этот код, проигнорируйте письмо.</p>
                </body>
                </html>
                """.formatted(code);

        return sendEmail(email, subject, html);
    }

    @Override
    public Mono<Void> sendRateChangeNotification(String email, String name, String bankName,
                                                  BigDecimal oldRate, BigDecimal newRate,
                                                  BigDecimal oldPayment, BigDecimal newPayment) {
        String subject = "Изменение ставки по ипотеке — %s".formatted(bankName);
        String html = """
                <html>
                <body>
                    <h2>Уважаемый(ая) %s!</h2>
                    <p>Банк <strong>%s</strong> изменил ставку по ипотеке.</p>
                    <table border="1" cellpadding="5" cellspacing="0">
                        <tr><th>Параметр</th><th>Было</th><th>Стало</th></tr>
                        <tr><td>Ставка</td><td>%.2f%%</td><td>%.2f%%</td></tr>
                        <tr><td>Ежемесячный платёж</td><td>%,.2f ₽</td><td>%,.2f ₽</td></tr>
                    </table>
                    <p>Рекомендуем пересчитать ваш сценарий с новой ставкой.</p>
                    <p><a href="%s/scenarios">Перейти в личный кабинет</a></p>
                </body>
                </html>
                """.formatted(name, bankName,
                oldRate.multiply(BigDecimal.valueOf(100)),
                newRate.multiply(BigDecimal.valueOf(100)),
                oldPayment, newPayment, baseUrl);

        return sendEmail(email, subject, html);
    }

    private Mono<Void> sendEmail(String to, String subject, String html) {
        return Mono.fromCallable(() -> {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}