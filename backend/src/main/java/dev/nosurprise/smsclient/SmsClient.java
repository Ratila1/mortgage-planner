package dev.nosurprise.smsclient;

import dev.nosurprise.smsclient.dto.CreateSmsRequest;
import dev.nosurprise.smsclient.dto.SmsResponse;
import dev.nosurprise.smsclient.dto.VerifyRequest;
import dev.nosurprise.smsclient.dto.VerifyResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;
import java.util.function.Supplier;

@Component
public class SmsClient {

    private static final Logger log = LoggerFactory.getLogger(SmsClient.class);

    private final RestClient restClient;
    private final ServiceJwt serviceJwt;
    private final CircuitBreaker circuitBreaker;

    public SmsClient(RestClient restClient, SmsClientProperties props, CircuitBreaker circuitBreaker) {
        this.restClient = restClient;
        this.serviceJwt = new ServiceJwt(props.serviceJwtSecret());
        this.circuitBreaker = circuitBreaker;
    }

    public SmsResponse createSms(String idempotencyKey, String phone) {
        return executeWithCircuitBreaker(() -> restClient.post()
            .uri("/api/v1/sms")
            .header("Idempotency-Key", idempotencyKey)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceJwt.mint())
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(new CreateSmsRequest(phone))
            .retrieve()
            .body(SmsResponse.class));
    }

    public SmsResponse getSms(UUID id) {
        return executeWithCircuitBreaker(() -> restClient.get()
            .uri("/api/v1/sms/{id}", id)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceJwt.mint())
            .retrieve()
            .body(SmsResponse.class));
    }

    public VerifyResponse verify(UUID id, String code) {
        return executeWithCircuitBreaker(() -> restClient.post()
            .uri("/api/v1/sms/{id}/verify", id)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceJwt.mint())
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(new VerifyRequest(code))
            .retrieve()
            .body(VerifyResponse.class));
    }

    /**
     * 429 от sms-verification-service — это его собственный лимит по номеру: пробрасываем как 429,
     * чтобы клиент не принимал его за «неверный код» или недоступность сервиса.
     */
    private <T> T executeWithCircuitBreaker(Supplier<T> supplier) {
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, supplier).get();
        } catch (CallNotPermittedException e) {
            log.warn("SMS client circuit breaker is OPEN, failing fast");
            throw new SmsClientUnavailableException("SMS verification service temporarily unavailable (circuit breaker open)", e);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("SMS verification service rate limited the request (429)");
            throw new SmsClientRateLimitedException("SMS service rate limit exceeded", e);
        } catch (RestClientResponseException e) {
            log.error("SMS verification service responded with {}", e.getStatusCode().value(), e);
            throw new SmsClientException("SMS verification service responded with " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            log.error("SMS verification service is unreachable", e);
            throw new SmsClientUnavailableException("SMS verification service is unreachable", e);
        } catch (Exception e) {
            log.error("SMS client call failed", e);
            throw new SmsClientException("SMS verification service call failed: " + e.getMessage(), e);
        }
    }
}