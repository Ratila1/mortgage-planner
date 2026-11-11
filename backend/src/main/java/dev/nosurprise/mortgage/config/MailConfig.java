package dev.nosurprise.mortgage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class MailConfig {

    @Bean
    @Profile("!test")
    public JavaMailSender javaMailSender(
            @org.springframework.beans.factory.annotation.Value("${spring.mail.host}") String host,
            @org.springframework.beans.factory.annotation.Value("${spring.mail.port}") int port,
            @org.springframework.beans.factory.annotation.Value("${spring.mail.username}") String username,
            @org.springframework.beans.factory.annotation.Value("${spring.mail.password}") String password) {

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);

        if (username != null && !username.isEmpty()) {
            sender.setUsername(username);
            sender.setPassword(password);

            Properties props = sender.getJavaMailProperties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "false");
            props.put("mail.smtp.connectiontimeout", "5000");
            props.put("mail.smtp.timeout", "5000");
            props.put("mail.smtp.writetimeout", "5000");
        }

        return sender;
    }

    @Bean
    @Profile("test")
    public JavaMailSender mockMailSender() {
        return new MockJavaMailSender();
    }

    // Simple mock for testing
    public static class MockJavaMailSender extends JavaMailSenderImpl {
        public void send(jakarta.mail.Message... messages) {
            // Do nothing in tests
        }

        public jakarta.mail.internet.MimeMessage createMimeMessage() {
            return new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null);
        }
    }
}







