package dev.nosurprise.mortgage.config;

import dev.nosurprise.mortgage.user.Scenario;
import dev.nosurprise.mortgage.user.ScheduleDocument;
import dev.nosurprise.mortgage.pdf.PdfExportTask;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

@Configuration
@EnableReactiveMongoRepositories(basePackages = {
        "dev.nosurprise.mortgage.user",
        "dev.nosurprise.mortgage.pdf"
})
public class MongoConfig {

    @Bean
    public ReactiveMongoTemplate reactiveMongoTemplate(org.springframework.data.mongodb.ReactiveMongoDatabaseFactory factory) {
        return new ReactiveMongoTemplate(factory);
    }

    // Indexes will be created automatically by @Indexed annotations or can be created programmatically
}







