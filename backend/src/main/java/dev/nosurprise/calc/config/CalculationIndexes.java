package dev.nosurprise.calc.config;

import dev.nosurprise.calc.repository.CalculationDocument;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.time.Duration;

/**
 * Индексы коллекции {@code calculations} (§6.2): создаются явно через IndexOperations.createIndex,
 * аннотации @Indexed / @CompoundIndex не используются.
 * TTL (expireAfterSeconds = 0) удаляет документ, как только expireAt прошёл; документы без expireAt
 * (расчёты пользователя) живут бессрочно.
 */
@Configuration
public class CalculationIndexes {

    @Bean(name = "calculationIndexesInitializer")
    InitializingBean calculationIndexes(MongoTemplate mongoTemplate) {
        return () -> {
            IndexOperations ops = mongoTemplate.indexOps(CalculationDocument.class);
            ops.createIndex(new Index().on("paramsHash", Sort.Direction.ASC).named("params_hash_idx"));
            ops.createIndex(new Index()
                    .on("userId", Sort.Direction.ASC)
                    .on("createdAt", Sort.Direction.DESC)
                    .named("user_created_at_idx"));
            ops.createIndex(new Index()
                    .on("expireAt", Sort.Direction.ASC)
                    .expire(Duration.ZERO)
                    .named("expire_at_ttl_idx"));
        };
    }
}
