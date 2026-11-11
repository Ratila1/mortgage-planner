package dev.nosurprise.mortgage.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class MetricsConfig {

    @Bean
    public Timer calculatorTimer(MeterRegistry registry) {
        return Timer.builder("mortgage.calculator.request")
                .description("Time spent calculating mortgage")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);
    }

    @Bean
    public Timer authTimer(MeterRegistry registry) {
        return Timer.builder("mortgage.auth.request")
                .description("Time spent on authentication operations")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Bean
    public Timer pdfExportTimer(MeterRegistry registry) {
        return Timer.builder("mortgage.pdf.export")
                .description("Time spent exporting PDF")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}







