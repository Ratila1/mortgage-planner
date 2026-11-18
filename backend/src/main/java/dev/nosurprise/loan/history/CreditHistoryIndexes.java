package dev.nosurprise.loan.history;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

/** Индексы credit_history (§4.9): история клиента и сводка по статусам. Создаются явно, как в calc. */
@Configuration
public class CreditHistoryIndexes {

    @Bean(name = "creditHistoryIndexesInitializer")
    InitializingBean creditHistoryIndexes(MongoTemplate mongoTemplate) {
        return () -> {
            IndexOperations ops = mongoTemplate.indexOps(CreditHistoryDocument.class);
            ops.createIndex(new Index()
                    .on("userId", Sort.Direction.ASC)
                    .on("submittedAt", Sort.Direction.DESC)
                    .named("user_submitted_at_idx"));
            ops.createIndex(new Index()
                    .on("userId", Sort.Direction.ASC)
                    .on("status", Sort.Direction.ASC)
                    .named("user_status_idx"));
        };
    }
}
