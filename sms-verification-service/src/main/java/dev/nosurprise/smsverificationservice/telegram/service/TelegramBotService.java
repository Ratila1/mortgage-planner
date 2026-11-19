package dev.nosurprise.smsverificationservice.telegram.service;

import dev.nosurprise.smsverificationservice.telegram.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Thin client for the Telegram Bot API. */
@Service
public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);

    private final TelegramProperties props;
    private final RestClient restClient;

    public TelegramBotService(TelegramProperties props) {
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(props.apiBase() + "/bot" + props.botToken())
                .build();
    }

    public void sendMessage(long chatId, String text) {
        restClient.post()
                .uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text))
                .retrieve()
                .toBodilessEntity();
    }

    public void sendContactRequest(long chatId, String text) {
        Map<String, Object> keyboard = Map.of(
                "keyboard", List.of(List.of(Map.of("text", "Поделиться номером", "request_contact", true))),
                "resize_keyboard", true,
                "one_time_keyboard", true);
        restClient.post()
                .uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text, "reply_markup", keyboard))
                .retrieve()
                .toBodilessEntity();
    }

    public void deleteWebhook() {
        try {
            restClient.post().uri("/deleteWebhook").retrieve().toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Could not delete Telegram webhook: {}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getUpdates(long offset, int timeoutSeconds) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uri -> uri.path("/getUpdates")
                            .queryParam("offset", offset)
                            .queryParam("timeout", timeoutSeconds)
                            .build())
                    .retrieve()
                    .body(Map.class);
            if (response == null) return List.of();
            if (response.get("result") instanceof List<?> updates) {
                return (List<Map<String, Object>>) (List<?>) updates;
            }
            return List.of();
        } catch (Exception ex) {
            log.warn("Telegram getUpdates failed: {}", ex.getMessage());
            return List.of();
        }
    }
}
