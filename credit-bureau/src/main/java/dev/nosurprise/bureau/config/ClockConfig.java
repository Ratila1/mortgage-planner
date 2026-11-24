package dev.nosurprise.bureau.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Время — только через Clock: в тестах подменяется (как в монолите, MICROLOANS.md §7.8). */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
