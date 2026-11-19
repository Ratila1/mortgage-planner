package dev.nosurprise.smsverificationservice.telegram.messaging;

import dev.nosurprise.smsverificationservice.telegram.config.TelegramProperties;
import dev.nosurprise.smsverificationservice.telegram.service.TelegramBotService;
import dev.nosurprise.smsverificationservice.telegram.service.TelegramUpdateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Local-development long polling; disabled in production where the webhook is used. */
@Component
@ConditionalOnProperty(name = "app.telegram.polling-enabled", havingValue = "true", matchIfMissing = false)
public class TelegramPollingRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingRunner.class);

    private final TelegramBotService bot;
    private final TelegramUpdateHandler handler;
    private final TelegramProperties props;

    public TelegramPollingRunner(TelegramBotService bot, TelegramUpdateHandler handler, TelegramProperties props) {
        this.bot = bot;
        this.handler = handler;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.botToken() == null || props.botToken().isBlank()) {
            log.warn("Telegram bot token is empty; long polling disabled");
            return;
        }
        bot.deleteWebhook();
        Thread.ofVirtual().name("telegram-polling").start(() -> {
            long offset = 0;
            while (!Thread.currentThread().isInterrupted()) {
                List<Map<String, Object>> updates = bot.getUpdates(offset, 20);
                for (Map<String, Object> update : updates) {
                    try {
                        handler.handle(update);
                    } catch (Exception ex) {
                        log.warn("Failed to handle Telegram update: {}", ex.getMessage());
                    }
                    if (update.get("update_id") instanceof Number id) {
                        offset = Math.max(offset, id.longValue() + 1);
                    }
                }
                if (updates.isEmpty()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
    }
}
