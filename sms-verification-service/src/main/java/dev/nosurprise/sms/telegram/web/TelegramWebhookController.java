package dev.nosurprise.sms.telegram.web;

import dev.nosurprise.sms.telegram.service.TelegramUpdateHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class TelegramWebhookController {

    private final TelegramUpdateHandler handler;

    public TelegramWebhookController(TelegramUpdateHandler handler) {
        this.handler = handler;
    }

    @PostMapping("/telegram/webhook")
    public Map<String, Object> handle(@RequestBody Map<String, Object> update) {
        handler.handle(update);
        return Map.of("ok", true);
    }
}
