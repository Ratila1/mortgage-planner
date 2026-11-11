package dev.nosurprise.integration.support;

import org.springframework.web.client.RestClient;

/**
 * HTTP-клиент integration-тестов: ходит напрямую в поднятый backend на случайном порту
 * (не через nginx/frontend).
 */
public class ApiClient {

    private final RestClient client;

    public ApiClient(int port) {
        this.client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    public RestClient client() {
        return client;
    }
}
