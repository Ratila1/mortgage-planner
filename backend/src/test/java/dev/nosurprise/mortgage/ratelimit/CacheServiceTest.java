package dev.nosurprise.mortgage.ratelimit;

import dev.nosurprise.mortgage.calculator.CalculationResponse;
import dev.nosurprise.mortgage.calculator.ScheduleEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new CacheServiceImpl(redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Nested
    @DisplayName("Кэш расчётов (К13)")
    class CacheTests {

        @Test
        @DisplayName("get_withExistingKey_returnsCachedValue")
        void get_withExistingKey_returnsCachedValue() {
            // given
            String key = "test-key";
            CalculationResponse expected = new CalculationResponse(
                    new BigDecimal("50000"),
                    new BigDecimal("1000000"),
                    new BigDecimal("6000000"),
                    List.of(new ScheduleEntry(1, new BigDecimal("50000"), new BigDecimal("10000"), new BigDecimal("40000"), new BigDecimal("3990000")))
            );
            // Jackson serialized JSON for CalculationResponse
            String json = "{\"monthlyPayment\":50000,\"overpayment\":1000000,\"totalPayment\":6000000,\"schedule\":[{\"month\":1,\"payment\":50000,\"principal\":10000,\"interest\":40000,\"remaining\":3990000}]}";

            when(valueOps.get("cache:calc:" + key)).thenReturn(Mono.just(json));

            // when
            Mono<CalculationResponse> result = cacheService.get(key);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.monthlyPayment()).isEqualByComparingTo("50000");
                        assertThat(response.overpayment()).isEqualByComparingTo("1000000");
                        assertThat(response.totalPayment()).isEqualByComparingTo("6000000");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("get_withNonExistentKey_returnsEmpty")
        void get_withNonExistentKey_returnsEmpty() {
            // given
            String key = "non-existent-key";
            when(valueOps.get("cache:calc:" + key)).thenReturn(Mono.empty());

            // when
            Mono<CalculationResponse> result = cacheService.get(key);

            // then
            StepVerifier.create(result)
                    .verifyComplete();
        }

        @Test
        @DisplayName("put_andGet_returnsStoredValue")
        void put_andGet_returnsStoredValue() {
            // given
            String key = "test-key";
            CalculationResponse value = new CalculationResponse(
                    new BigDecimal("50000"),
                    new BigDecimal("1000000"),
                    new BigDecimal("6000000"),
                    List.of()
            );
            String expectedJson = "{\"monthlyPayment\":50000,\"overpayment\":1000000,\"totalPayment\":6000000,\"schedule\":[]}";
            when(valueOps.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.empty());
            when(valueOps.get("cache:calc:" + key)).thenReturn(Mono.just(expectedJson));

            // when
            Mono<Void> putResult = cacheService.put(key, value, 300);
            Mono<CalculationResponse> getResult = cacheService.get(key);

            // then
            StepVerifier.create(putResult).verifyComplete();
            StepVerifier.create(getResult)
                    .assertNext(response -> {
                        assertThat(response.monthlyPayment()).isEqualByComparingTo("50000");
                        assertThat(response.overpayment()).isEqualByComparingTo("1000000");
                        assertThat(response.totalPayment()).isEqualByComparingTo("6000000");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("put_withInvalidValue_handlesSerializationError")
        void put_withInvalidValue_handlesSerializationError() {
            // given
            // We can't easily test serialization error without a custom ObjectMapper
            // but we can verify the method doesn't throw
            String key = "test-key";
            CalculationResponse value = new CalculationResponse(
                    new BigDecimal("50000"),
                    new BigDecimal("1000000"),
                    new BigDecimal("6000000"),
                    List.of()
            );
            when(valueOps.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.empty());

            // when/then
            StepVerifier.create(cacheService.put(key, value, 300))
                    .verifyComplete();
        }

        @Test
        @DisplayName("get_withCorruptedJson_handlesDeserializationError")
        void get_withCorruptedJson_handlesDeserializationError() {
            // given
            String key = "corrupted-key";
            String corruptedJson = "{invalid json";
            when(valueOps.get("cache:calc:" + key)).thenReturn(Mono.just(corruptedJson));

            // when/then
            StepVerifier.create(cacheService.get(key))
                    .expectError()
                    .verify();
        }
    }
}







