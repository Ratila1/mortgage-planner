package dev.nosurprise.smsverificationservice.telegram.service;

import dev.nosurprise.smsverificationservice.telegram.dto.UserByPhoneResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/** Internal HTTP calls to the main backend, protected by the shared service token. */
@Component
public class MainBackendClient {

    private static final Logger log = LoggerFactory.getLogger(MainBackendClient.class);

    private final RestClient restClient;

    public MainBackendClient(@Value("${app.main-backend.base-url}") String baseUrl,
                             @Value("${app.service.token}") String serviceToken) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Service-Token", serviceToken)
                .build();
    }

    public Optional<UUID> findUserIdByPhone(String phone) {
        try {
            UserByPhoneResponse response = restClient.get()
                    .uri(uri -> uri.path("/api/internal/users/by-phone")
                            .queryParam("phone", phone)
                            .build())
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
