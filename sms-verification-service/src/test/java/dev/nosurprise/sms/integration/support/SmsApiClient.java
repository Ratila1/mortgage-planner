package dev.nosurprise.sms.integration.support;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * HTTP-клиент integration-тестов: ходит напрямую в sms-verification-service
 * (не через nginx/frontend) и не бросает исключения на 4xx/5xx — статусы
 * проверяются в тестах. Все вызовы подписываются service-JWT, кроме тех,
 * где тест явно передаёт null, чтобы проверить 401.
 */
public class SmsApiClient {

    private final RestClient client;
    private final String defaultToken;

    public SmsApiClient(int port, String defaultToken) {
        this.client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
        this.defaultToken = defaultToken;
    }

    public ResponseEntity<String> create(String idempotencyKey, String phone) {
        return create(idempotencyKey, phone, defaultToken);
    }

    public ResponseEntity<String> create(String idempotencyKey, String phone, String bearerToken) {
        RestClient.RequestBodySpec spec = client.post()
                .uri("/api/v1/sms")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"phone\":\"" + phone + "\"}");
        if (idempotencyKey != null) {
            spec.header("Idempotency-Key", idempotencyKey);
        }
        return exchange(authorize(spec, bearerToken));
    }

    public ResponseEntity<String> get(UUID id) {
        return get(id, defaultToken);
    }

    public ResponseEntity<String> get(UUID id, String bearerToken) {
        return exchange(authorize(client.get().uri("/api/v1/sms/" + id), bearerToken));
    }

    public ResponseEntity<String> verify(UUID id, String code, String bearerToken) {
        RestClient.RequestBodySpec spec = client.post()
                .uri("/api/v1/sms/" + id + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"" + code + "\"}");
        return exchange(authorize(spec, bearerToken));
    }

    private static RestClient.RequestHeadersSpec<?> authorize(RestClient.RequestHeadersSpec<?> spec, String token) {
        return token == null ? spec : spec.header("Authorization", "Bearer " + token);
    }

    private static ResponseEntity<String> exchange(RestClient.RequestHeadersSpec<?> spec) {
        return spec.exchange((request, response) -> {
            byte[] body = response.getBody().readAllBytes();
            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.length == 0 ? null : new String(body, StandardCharsets.UTF_8));
        });
    }
}
