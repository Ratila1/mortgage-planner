package dev.nosurprise.notificationservice.service;

import dev.nosurprise.notificationservice.config.TelegramProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
                "keyboard", java.util.List.of(java.util.List.of(Map.of("text", "Поделиться номером", "request_contact", true))),
                "resize_keyboard", true,
                "one_time_keyboard", true);
        restClient.post().uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text, "reply_markup", keyboard))
                .retrieve().toBodilessEntity();
    }
}