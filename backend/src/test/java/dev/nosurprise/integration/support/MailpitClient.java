package dev.nosurprise.integration.support;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP-клиент Mailpit для integration-тестов: письма читаются из реального SMTP-приёмника
 * (контейнер Mailpit), без моков mail pipeline. Тела сообщений декодируются как UTF-8,
 * поэтому кириллица в письмах сравнивается без искажений.
 */
public final class MailpitClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public MailpitClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = RestClient.builder()
                .baseUrl(TestContainersEnvironment.mailpitApiUrl())
                .build();
    }

    /** Все письма, отправленные на адрес (без учёта регистра). */
    public List<Mail> messagesTo(String recipient) {
        List<Mail> messages = new ArrayList<>();
        JsonNode list = json("/api/v1/messages?limit=1000").get("messages");
        if (list == null || list.isNull()) {
            return messages;
        }
        for (JsonNode message : list) {
            if (sentTo(message.get("To"), recipient)) {
                String id = message.get("ID").asString();
                messages.add(new Mail(id, message.get("Subject").asString(), text(id)));
            }
        }
        return messages;
    }

    /** Письма на адрес, тело которых содержит фрагмент текста. */
    public List<Mail> messagesTo(String recipient, String bodyFragment) {
        return messagesTo(recipient).stream()
                .filter(mail -> mail.text().contains(bodyFragment))
                .toList();
    }

    private boolean sentTo(JsonNode to, String recipient) {
        if (to == null || to.isNull()) {
            return false;
        }
        for (JsonNode address : to) {
            JsonNode value = address.get("Address");
            if (value != null && recipient.equalsIgnoreCase(value.asString())) {
                return true;
            }
        }
        return false;
    }

    private String text(String messageId) {
        JsonNode text = json("/api/v1/message/" + messageId).get("Text");
        return text == null || text.isNull() ? "" : text.asString();
    }

    private JsonNode json(String path) {
        byte[] body = client.get().uri(path).retrieve().body(byte[].class);
        return objectMapper.readTree(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));
    }

    public record Mail(String id, String subject, String text) {
    }
}
