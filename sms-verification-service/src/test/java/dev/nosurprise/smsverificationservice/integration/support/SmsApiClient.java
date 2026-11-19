package dev.nosurprise.smsverificationservice.integration.support;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * HTTP-клиент integration-тестов: ходит напрямую в sms-verification-service
 * (не через nginx/frontend) и не бросает исключения на 4xx/5xx — статусы
 * проверяются в тестах.
 */
public class SmsApiClient {

    private final RestClient client;

    public SmsApiClient(int port) {
        this.client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    public ResponseEntity<String> create(String idempotencyKey, String phone) {
        RestClient.RequestBodySpec spec = client.post()
                .uri("/api/v1/sms")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"phone\":\"" + phone + "\"}");
        if (idempotencyKey != null) {
            spec.header("Idempotency-Key", idempotencyKey);
        }
        return exchange(spec);
    }

    public ResponseEntity<String> get(UUID id) {
        return exchange(client.get().uri("/api/v1/sms/" + id));
    }

    public ResponseEntity<String> verify(UUID id, String code, String bearerToken) {
        RestClient.RequestBodySpec spec = client.post()
                .uri("/api/v1/sms/" + id + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"" + code + "\"}");
        if (bearerToken != null) {
            spec.header("Authorization", "Bearer " + bearerToken);
        }
        return exchange(spec);
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
