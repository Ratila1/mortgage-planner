package dev.nosurprise.integration.support;

import dev.nosurprise.common.sms.SmsSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SMS для integration-тестов: запоминает последнее сообщение на номер, чтобы тест прочитал код. */
public class RecordingSmsSender implements SmsSender {

    private static final Pattern CODE = Pattern.compile("(\\d{6})");
    private final Map<String, String> last = new ConcurrentHashMap<>();

    @Override
    public void send(String phone, String text) {
        last.put(phone, text);
    }

    public String lastCode(String phone) {
        String text = last.get(phone);
        if (text == null) {
            throw new AssertionError("SMS на " + phone + " не отправлялось");
        }
        Matcher matcher = CODE.matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("в SMS нет кода: " + text);
        }
        return matcher.group(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        RecordingSmsSender recordingSmsSender() {
            return new RecordingSmsSender();
        }
    }
}
