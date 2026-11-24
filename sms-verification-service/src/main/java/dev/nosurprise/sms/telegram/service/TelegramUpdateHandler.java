package dev.nosurprise.sms.telegram.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/** Handles Telegram updates coming from either the webhook or local long polling. */
@Service
public class TelegramUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final BindingService bindings;
    private final TelegramBotService bot;

    public TelegramUpdateHandler(StringRedisTemplate redis, BindingService bindings, TelegramBotService bot) {
        this.redis = redis;
        this.bindings = bindings;
        this.bot = bot;
    }

    public void handle(Map<String, Object> update) {
        Object updateId = update.get("update_id");
        String dedupKey = updateId == null ? null : "tg:update:" + updateId;
        if (dedupKey != null) {
            Boolean isNew = redis.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(isNew)) return;
        }
        try {
            dispatch(update);
        } catch (RuntimeException ex) {
            // Апдейт не обработан — снимаем отметку, иначе повторная доставка от Telegram была бы
            // молча проглочена и сообщение пользователя потерялось бы.
            if (dedupKey != null) redis.delete(dedupKey);
            throw ex;
        }
    }

    private void dispatch(Map<String, Object> update) {
        if (!(update.get("message") instanceof Map<?, ?> message)) return;

        if (message.get("contact") instanceof Map<?, ?> contact) {
            handleContact(message, contact);
        } else if ("/start".equals(message.get("text"))) {
            Long chatId = telegramId(message.get("from"));
            if (chatId != null) {
                bot.sendContactRequest(chatId, "Поделитесь номером телефона для привязки аккаунта.");
            }
        }
    }

    private void handleContact(Map<?, ?> message, Map<?, ?> contact) {
        Long telegramId = telegramId(message.get("from"));
        if (telegramId == null) return;

        Object contactUserId = contact.get("user_id");
        if (contactUserId instanceof Number contactId && contactId.longValue() != telegramId) {
            bot.sendMessage(telegramId, "Контакт должен принадлежать тому же аккаунту Telegram.");
            return;
        }
        Object phone = contact.get("phone_number");
        if (phone == null) return;

        try {
            bindings.bindContact(telegramId, String.valueOf(phone), username(message.get("from")));
        } catch (Exception ex) {
            log.warn("Telegram binding failed for chat {}: {}", telegramId, ex.getMessage());
            bot.sendMessage(telegramId, "Не удалось привязать аккаунт. Попробуйте позже.");
        }
    }

    private static Long telegramId(Object from) {
        if (from instanceof Map<?, ?> user && user.get("id") instanceof Number id) return id.longValue();
        return null;
    }

    private static String username(Object from) {
        if (from instanceof Map<?, ?> user && user.get("username") instanceof String name && !name.isBlank()) {
            return name;
        }
        return null;
    }
}
