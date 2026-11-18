package dev.nosurprise.smsclient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(SmsClientProperties.class)
public class SmsClientConfig {

    @Bean
    public RestClient smsRestClient(SmsClientProperties props) {
        var httpClient = HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.readTimeout());
        return RestClient.builder()
            .baseUrl(props.baseUrl())
            .requestFactory(requestFactory)
            .build();
    }

    @Bean
    public CircuitBreaker smsCircuitBreaker(SmsClientProperties props) {
        if (!props.circuitBreaker().enabled()) {
            return CircuitBreakerRegistry.ofDefaults().circuitBreaker("sms-client-disabled");
        }
        var config = CircuitBreakerConfig.custom()
            .failureRateThreshold(props.circuitBreaker().failureRateThreshold())
            .slidingWindowSize(props.circuitBreaker().slidingWindowSize())
            .waitDurationInOpenState(props.circuitBreaker().waitDurationInOpenState())
            .permittedNumberOfCallsInHalfOpenState(props.circuitBreaker().permittedNumberOfCallsInHalfOpenState())
            // 4xx (в первую очередь 429 по лимиту номера) — ответ сервиса, а не его отказ:
            // такие вызовы не должны открывать предохранитель.
            .ignoreExceptions(HttpClientErrorException.class)
            .build();
        var registry = CircuitBreakerRegistry.of(config);
        return registry.circuitBreaker("sms-client");
    }
}
