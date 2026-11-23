package dev.nosurprise.smsclient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Маппинг ответов sms-verification-service в исключения backend:
 * 429 → 429 RATE_LIMITED, остальные HTTP-ошибки → 502 SMS_DELIVERY_FAILED, открытый предохранитель → 502.
 */
class SmsClientTest {

    private static final String BASE_URL = "http://sms.test";
    private static final String SECRET = "test-service-jwt-secret-at-least-32-chars";

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;
    private SmsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new SmsClientProperties(BASE_URL, SECRET, Duration.ofSeconds(1), Duration.ofSeconds(2),
                new SmsClientProperties.CircuitBreakerConfig(true, 50, 10, Duration.ofSeconds(10), 3));
        circuitBreaker = CircuitBreakerRegistry.ofDefaults().circuitBreaker("test");
        client = new SmsClient(builder.build(), props, circuitBreaker);
    }

    @Test
    @DisplayName("201/202 от sms-сервиса → SmsResponse")
    void success() {
        server.expect(requestTo(BASE_URL + "/api/v1/sms"))
                // Контракт с sms-verification-service: без service-JWT эндпоинт отвечает 401.
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Authorization"))
                        .as("service JWT must be attached")
                        .startsWith("Bearer "))
                .andRespond(withSuccess("""
                        {"id":"11111111-1111-1111-1111-111111111111","status":"IN_PROGRESS","attempts":0}
                        """, MediaType.APPLICATION_JSON));

        var response = client.createSms("key-1", "+375291234567");

        assertThat(response.getId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        server.verify();
    }

    @Test
    @DisplayName("429 от sms-сервиса → SmsClientRateLimitedException (клиенту уйдёт 429, а не 400/502)")
    void rateLimited() {
        server.expect(requestTo(BASE_URL + "/api/v1/sms"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.createSms("key-2", "+375291234567"))
                .isInstanceOf(SmsClientRateLimitedException.class)
                .isInstanceOf(SmsClientException.class);
    }

    @Test
    @DisplayName("5xx от sms-сервиса → SmsClientException (клиенту уйдёт 502 SMS_DELIVERY_FAILED)")
    void serverError() {
        server.expect(requestTo(BASE_URL + "/api/v1/sms"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.createSms("key-3", "+375291234567"))
                .isInstanceOf(SmsClientException.class)
                .isNotInstanceOf(SmsClientRateLimitedException.class);
    }

    @Test
    @DisplayName("открытый предохранитель → SmsClientUnavailableException")
    void circuitBreakerOpen() {
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> client.createSms("key-4", "+375291234567"))
                .isInstanceOf(SmsClientUnavailableException.class);
    }
}
