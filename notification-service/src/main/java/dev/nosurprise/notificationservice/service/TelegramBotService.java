package dev.nosurprise.notificationservice.service;

import dev.nosurprise.notificationservice.config.TelegramProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class TelegramBotService {
    private final RestClient restClient;
    private final TelegramProperties props;

    public TelegramBotService(TelegramProperties props) {
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(props.apiBase() + "/bot" + props.botToken())
                .build();
    }

    public void sendMessage(long chatId, String text) {
        restClient.post().uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text))
                .retrieve().toBodilessEntity();
    }

    /** Отправка с кнопкой "Поделиться номером" (request_contact). */
    public void sendContactRequest(long chatId, String text) {
        Map<String, Object> keyboard = Map.of(
                "keyboard", List.of(List.of(Map.of("text", "Поделиться номером", "request_contact", true))),
                "resize_keyboard", true,
                "one_time_keyboard", true);
        restClient.post().uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text, "reply_markup", keyboard))
                .retrieve().toBodilessEntity();
    }

    /** Удалить webhook, чтобы работал long polling. */
    public void deleteWebhook() {
        try {
            restClient.post().uri("/deleteWebhook").retrieve().toBodilessEntity();
        } catch (Exception ignored) {
        }
    }

    /** Long polling: получить апдейты начиная с offset. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getUpdates(long offset, int timeoutSeconds) {
        try {
            Map<String, Object> resp = restClient.get()
                    .uri(u -> u.path("/getUpdates")
                            .queryParam("offset", offset)
                            .queryParam("timeout", timeoutSeconds)
                            .build())
                    .retrieve().body(Map.class);
            if (resp == null) return List.of();
            Object result = resp.get("result");
            if (result instanceof List<?> list) {
                return (List<Map<String, Object>>) (List<?>) list;
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}