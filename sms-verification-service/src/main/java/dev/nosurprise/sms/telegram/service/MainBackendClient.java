package dev.nosurprise.sms.telegram.service;

import dev.nosurprise.sms.telegram.dto.UserByPhoneResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Internal HTTP calls to the main backend, protected by the shared service token. */
@Component
public class MainBackendClient {

    private static final Logger log = LoggerFactory.getLogger(MainBackendClient.class);

    private final RestClient restClient;

    /**
     * Таймауты обязательны: без них зависший backend держал бы поток dispatch/Telegram
     * бесконечно (RestClient по умолчанию ждёт вечно).
     */
    public MainBackendClient(@Value("${app.main-backend.base-url}") String baseUrl,
                             @Value("${app.main-backend.timeout:PT3S}") Duration timeout,
                             @Value("${app.service.token}") String serviceToken) {
        var httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Service-Token", serviceToken)
                .build();
    }

    public Optional<UUID> findUserIdByPhone(String phone) {
        try {
            UserByPhoneResponse response = restClient.get()
                    .uri(uri -> uri.path("/api/internal/users/by-phone")
                            // URI variable expansion percent-encodes '+'; a raw '+' is decoded
                            // as a space by the servlet container and the lookup misses.
                            .queryParam("phone", "{phone}")
                            .build(Map.of("phone", phone)))
                    .retrieve()
                    .body(UserByPhoneResponse.class);
            return response == null ? Optional.empty() : Optional.ofNullable(response.userId());
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (HttpClientErrorException.Conflict ex) {
            throw new AmbiguousPhoneException();
        } catch (Exception ex) {
            log.warn("User lookup by phone failed: {}", ex.getMessage());
            throw new IllegalStateException("Could not look up user by phone", ex);
        }
    }

    public void completeVerification(UUID verificationId) {
        restClient.post()
                .uri("/api/internal/verifications/{id}/complete", verificationId)
                .retrieve()
                .onStatus(status -> status.value() >= 400, (request, response) -> {
                    throw new IllegalStateException(
                            "Backend rejected verification completion: HTTP " + response.getStatusCode().value());
                })
                .toBodilessEntity();
    }
}
