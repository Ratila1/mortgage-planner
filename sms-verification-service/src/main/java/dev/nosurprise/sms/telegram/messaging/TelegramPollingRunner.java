package dev.nosurprise.sms.telegram.messaging;

import dev.nosurprise.sms.telegram.config.TelegramProperties;
import dev.nosurprise.sms.telegram.service.TelegramBotService;
import dev.nosurprise.sms.telegram.service.TelegramUpdateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Local-development long polling; disabled by default, because deleting the webhook on startup
 * would silently switch a production bot off the webhook it is configured with.
 */
@Component
@ConditionalOnProperty(name = "app.telegram.polling-enabled", havingValue = "true", matchIfMissing = false)
public class TelegramPollingRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingRunner.class);
    private static final long IDLE_PAUSE_MS = 1_000;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 60_000;
    private static final int LONG_POLL_SECONDS = 20;

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
        Thread.ofVirtual().name("telegram-polling").start(this::pollLoop);
    }

    private void pollLoop() {
        long offset = 0;
        long backoffMs = INITIAL_BACKOFF_MS;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<Map<String, Object>> updates = bot.getUpdates(offset, LONG_POLL_SECONDS);
                backoffMs = INITIAL_BACKOFF_MS;
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
                    sleep(IDLE_PAUSE_MS);
                }
            } catch (RuntimeException ex) {
                // Ошибка Bot API (неверный токен, сеть) не должна превращаться в шторм логов: ждём дольше.
                log.warn("Telegram polling failed, retry in {} ms: {}", backoffMs, ex.getMessage());
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
