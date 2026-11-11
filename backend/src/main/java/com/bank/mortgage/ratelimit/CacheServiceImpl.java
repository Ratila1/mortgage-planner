package com.bank.mortgage.ratelimit;

import com.bank.mortgage.calculator.CalculationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public class CacheServiceImpl implements CacheService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheServiceImpl(ReactiveRedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<CalculationResponse> get(String key) {
        String redisKey = "cache:calc:" + key;
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(json -> {
                    try {
                        return Mono.just(objectMapper.readValue(json, CalculationResponse.class));
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                });
    }

    @Override
    public Mono<Void> put(String key, CalculationResponse value, long ttlSeconds) {
        String redisKey = "cache:calc:" + key;
        try {
            String json = objectMapper.writeValueAsString(value);
            return redisTemplate.opsForValue().set(redisKey, json, Duration.ofSeconds(ttlSeconds)).then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}