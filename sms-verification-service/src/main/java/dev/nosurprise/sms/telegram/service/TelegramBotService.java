package dev.nosurprise.sms.telegram.service;

import dev.nosurprise.sms.telegram.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin client for the Telegram Bot API.
 *
 * <p>URL содержит токен бота ({@code /bot<token>/...}), поэтому исключения никогда не прокидываются
 * наружу как есть: сообщение RestClient содержит URI с токеном и утекло бы в лог.
 */
@Service
public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);

    /** Long polling держит соединение до 20 с, поэтому у polling-клиента отдельный, увеличенный таймаут. */
    private static final Duration POLLING_READ_TIMEOUT = Duration.ofSeconds(30);

    private final TelegramProperties props;
    private final RestClient restClient;
    private final RestClient pollingClient;

    public TelegramBotService(TelegramProperties props) {
        this.props = props;
        String baseUrl = props.apiBase() + "/bot" + props.botToken();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(props.timeout()))
                .build();
        this.pollingClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(POLLING_READ_TIMEOUT))
                .build();
    }

    public void sendMessage(long chatId, String text) {
        try {
            restClient.post()
                    .uri("/sendMessage")
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            throw failure("sendMessage", ex);
        }
    }

    public void sendContactRequest(long chatId, String text) {
        Map<String, Object> keyboard = Map.of(
                "keyboard", List.of(List.of(Map.of("text", "Поделиться номером", "request_contact", true))),
                "resize_keyboard", true,
                "one_time_keyboard", true);
        try {
            restClient.post()
                    .uri("/sendMessage")
                    .body(Map.of("chat_id", chatId, "text", text, "reply_markup", keyboard))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            throw failure("sendContactRequest", ex);
        }
    }

    public void deleteWebhook() {
        try {
            restClient.post().uri("/deleteWebhook").retrieve().toBodilessEntity();
        } catch (RuntimeException ex) {
            log.warn("Could not delete Telegram webhook: {}", sanitize(ex));
        }
    }

    /** Ошибка поднимается наверх: цикл long polling сам решает, как долго ждать перед повтором. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getUpdates(long offset, int timeoutSeconds) {
        try {
            Map<String, Object> response = pollingClient.get()
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
        } catch (RuntimeException ex) {
            throw failure("getUpdates", ex);
        }
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration readTimeout) {
        // Bot API — HTTP/1.1. Апгрейд h2c на незащищённом соединении не даёт выигрыша, а прокси
        // и стабы (WireMock) получают chunked-тело вместе с Upgrade и обрывают запрос.
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(readTimeout)
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    private IllegalStateException failure(String operation, RuntimeException ex) {
        log.warn("Telegram {} failed: {}", operation, sanitize(ex));
        // Причина намеренно не прикладывается: её message содержит URI с токеном бота.
        return new IllegalStateException("Telegram " + operation + " failed");
    }

    private String sanitize(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        String token = props.botToken();
        return token == null || token.isBlank() ? message : message.replace(token, "***");
    }
}
