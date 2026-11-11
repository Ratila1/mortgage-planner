package com.bank.mortgage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.bank.mortgage.calculator.CalculationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class RedisConfig {

    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(ReactiveRedisConnectionFactory factory) {
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<CalculationResponse> jsonSerializer = 
                new Jackson2JsonRedisSerializer<>(CalculationResponse.class);
        
        RedisSerializationContext<String, String> context = RedisSerializationContext
                .<String, String>newSerializationContext(stringSerializer)
                .value(stringSerializer)
                .build();
        
        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public ReactiveRedisTemplate<String, CalculationResponse> calculationResponseRedisTemplate(
            ReactiveRedisConnectionFactory factory, ObjectMapper objectMapper) {
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<CalculationResponse> jsonSerializer = 
                new Jackson2JsonRedisSerializer<>(objectMapper, CalculationResponse.class);
        
        RedisSerializationContext<String, CalculationResponse> context = RedisSerializationContext
                .<String, CalculationResponse>newSerializationContext(stringSerializer)
                .value(jsonSerializer)
                .build();
        
        return new ReactiveRedisTemplate<>(factory, context);
    }
}