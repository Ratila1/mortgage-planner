package dev.nosurprise.notificationservice.web;

import dev.nosurprise.notificationservice.service.BindingService;
import dev.nosurprise.notificationservice.service.TelegramBotService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class TelegramWebhookController {
    private final StringRedisTemplate redis;
    private final BindingService bindingService;
    private final TelegramBotService bot;

    public TelegramWebhookController(StringRedisTemplate redis, BindingService bindingService, TelegramBotService bot) {
        this.redis = redis;
        this.bindingService = bindingService;
        this.bot = bot;
    }

    @PostMapping("/telegram/webhook")
    public Map<String, Object> handle(@RequestBody Map<String, Object> update) {
        Object updateId = update.get("update_id");
        if (updateId != null) {
            Boolean isNew = redis.opsForValue().setIfAbsent("tg:update:" + updateId, "1", java.time.Duration.ofHours(24));
            if (!Boolean.TRUE.equals(isNew)) return Map.of("ok", true); // дедуп
        }
        Object message = update.get("message");
        if (message instanceof Map<?, ?> m && m.get("contact") instanceof Map<?, ?> contact) {
            Object from = m.get("from");
            Long telegramId = from instanceof Map<?, ?> f && f.get("id") instanceof Number n ? n.longValue() : null;
            String phone = String.valueOf(contact.get("phone_number"));
            String username = from instanceof Map<?, ?> f ? String.valueOf(f.get("username")) : null;
            // lookup userId в основном бэкенде опущен в скелете — см. CONTRACT.md
            if (telegramId != null) {
                bot.sendMessage(telegramId, "Номер получен: " + phone + ". Привязка будет завершена после синхронизации с основным сервисом.");
            }
        } else if (message instanceof Map<?, ?> m && "/start".equals(m.get("text"))) {
            Object from = m.get("from");
            Long chatId = from instanceof Map<?, ?> f && f.get("id") instanceof Number n ? n.longValue() : null;
            if (chatId != null) {
                bot.sendContactRequest(chatId, "Поделитесь номером телефона для привязки аккаунта.");
            }
        }
        return Map.of("ok", true);
    }
}