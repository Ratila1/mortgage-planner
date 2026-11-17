package dev.nosurprise.loan.history;

import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Кредитная история в MongoDB (§4.9). Документ не правится по кусочкам ($set/$push), а заменяется целиком
 * с условием lastEventId < N: старое или повторное событие не откатит документ, при гонке побеждает более свежая версия.
 */
@Component
public class MongoCreditHistoryStore implements CreditHistoryStore {

    private static final String COLLECTION = "credit_history";

    private final MongoTemplate mongo;

    public MongoCreditHistoryStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean replaceIfNewer(CreditHistoryDocument document) {
        Query newerThanStored = Query.query(Criteria.where("_id").is(document.id())
                .and("lastEventId").lt(document.lastEventId()));
        UpdateResult result = mongo.replace(newerThanStored, document);
        if (result.getMatchedCount() > 0) {
            return true;
        }
        try {
            // Документа ещё нет — вставляем. Если он есть, но не старее нашего, вставка упадёт по _id.
            mongo.insert(document);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public List<CreditHistoryDocument> findByUser(UUID userId) {
        return mongo.find(Query.query(Criteria.where("userId").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "submittedAt")), CreditHistoryDocument.class);
    }

    @Override
    public long count() {
        return mongo.count(new Query(), CreditHistoryDocument.class);
    }

    @Override
    public Set<UUID> existingIds(Collection<UUID> ids) {
        // Только _id: читаем сырые bson-документы, а не record (у неполного record не собрать примитивы).
        Query query = Query.query(Criteria.where("_id").in(ids));
        query.fields().include("_id");
        return mongo.find(query, org.bson.Document.class, COLLECTION).stream()
                .map(document -> document.get("_id", UUID.class))
                .collect(Collectors.toSet());
    }
}
