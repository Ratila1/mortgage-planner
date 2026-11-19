package dev.nosurprise.smsverificationservice.telegram.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramUpdateHandlerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final BindingService bindings = mock(BindingService.class);
    private final TelegramBotService bot = mock(TelegramBotService.class);
    private final TelegramUpdateHandler handler = new TelegramUpdateHandler(redis, bindings, bot);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    @DisplayName("/start предлагает поделиться номером телефона")
    void startSendsContactRequest() {
        handler.handle(Map.of(
                "update_id", 1,
                "message", Map.of("text", "/start", "from", Map.of("id", 42L))));

        verify(bot).sendContactRequest(eq(42L), contains("номером"));
        verifyNoInteractions(bindings);
    }

    @Test
    @DisplayName("повтор того же update_id игнорируется")
    void duplicateUpdateIsIgnored() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        handler.handle(Map.of(
                "update_id", 1,
                "message", Map.of("text", "/start", "from", Map.of("id", 42L))));

        verify(values).setIfAbsent(eq("tg:update:1"), eq("1"), eq(Duration.ofHours(24)));
        verifyNoInteractions(bot, bindings);
    }

    @Test
    @DisplayName("контакт привязывает телефон к аккаунту Telegram")
    void contactBindsPhone() {
        handler.handle(Map.of(
                "update_id", 2,
                "message", Map.of(
                        "from", Map.of("id", 42L, "username", "ivan"),
                        "contact", Map.of("user_id", 42L, "phone_number", "+375291234567"))));

        verify(bindings).bindContact(42L, "+375291234567", "ivan");
    }

    @Test
    @DisplayName("контакт другого аккаунта Telegram отклоняется")
    void contactOfAnotherUserIsRejected() {
        handler.handle(Map.of(
                "update_id", 3,
                "message", Map.of(
                        "from", Map.of("id", 42L),
                        "contact", Map.of("user_id", 43L, "phone_number", "+375291234567"))));

        verify(bot).sendMessage(eq(42L), contains("аккаунту Telegram"));
        verifyNoInteractions(bindings);
    }

    @Test
    @DisplayName("апдейт без сообщения не делает ничего")
    void updateWithoutMessageIsIgnored() {
        handler.handle(Map.of("update_id", 4));

        verifyNoInteractions(bot, bindings);
    }
}
