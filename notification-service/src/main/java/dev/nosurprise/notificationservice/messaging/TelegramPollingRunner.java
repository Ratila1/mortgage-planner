package dev.nosurprise.notificationservice.messaging;

import dev.nosurprise.notificationservice.service.TelegramBotService;
import dev.nosurprise.notificationservice.web.TelegramWebhookController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Long polling для локальной разработки: бот сам забирает апдейты через getUpdates,
 * не нужен публичный webhook/туннель. Включается свойством app.telegram.polling-enabled=true (по умолчанию).
 * В продакшене (webhook) отключается: TELEGRAM_POLLING_ENABLED=false.
 */
@Component
@ConditionalOnProperty(name = "app.telegram.polling-enabled", havingValue = "true", matchIfMissing = true)
public class TelegramPollingRunner implements ApplicationRunner {

    private final TelegramBotService bot;
    private final TelegramWebhookController controller;

    public TelegramPollingRunner(TelegramBotService bot, TelegramWebhookController controller) {
        this.bot = bot;
        this.controller = controller;
    }

    @Override
    public void run(ApplicationArguments args) {
        bot.deleteWebhook();
        Thread.ofVirtual().name("telegram-polling").start(() -> {
            long offset = 0;
            while (!Thread.currentThread().isInterrupted()) {
                List<Map<String, Object>> updates = bot.getUpdates(offset, 20);
                for (Map<String, Object> update : updates) {
                    try {
                        controller.handle(update);
                    } catch (Exception ignored) {
                    }
                    Object id = update.get("update_id");
                    if (id instanceof Number n) {
                        offset = Math.max(offset, n.longValue() + 1);
                    }
                }
                if (updates.isEmpty()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
    }
}