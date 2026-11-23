package dev.nosurprise.bureau.report;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Займы заёмщика из Mongo, коллекция credit_history (CREDIT_BUREAU.md §7.2, шаг 6): один документ на заявку,
 * пишет монолит. Бюро читает сырой bson с проекцией только нужных полей — так формат монолита целиком
 * (ПДН, комментарии и т. п.) не становится зависимостью бюро. Под запрос есть индекс userId + status.
 */
@Component
public class CreditHistoryReader {

    static final String COLLECTION = "credit_history";
    /** Кредитная история — только займы (§3.1.1): Mongo хранит все статусы заявки. */
    static final List<String> LOAN_STATUSES = List.of("ACTIVE", "REPAID");

    private final MongoTemplate mongo;

    public CreditHistoryReader(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public StoredHistory find(UUID userId) {
        Query query = Query.query(Criteria.where("userId").is(userId).and("status").in(LOAN_STATUSES))
                .with(Sort.by(Sort.Direction.ASC, "submittedAt"));
        query.fields().include("status", "loan.amount", "submittedAt", "decidedAt", "closedAt");
        List<StoredHistory.StoredLoan> loans = mongo.find(query, Document.class, COLLECTION).stream()
                .map(CreditHistoryReader::loan)
                .toList();
        return new StoredHistory(loans);
    }

    static StoredHistory.StoredLoan loan(Document loan) {
        Document terms = loan.get("loan", Document.class);
        return new StoredHistory.StoredLoan(loan.getString("status"),
                terms == null ? null : money(terms.get("amount")),
                instant(loan.get("submittedAt")), instant(loan.get("decidedAt")), instant(loan.get("closedAt")));
    }

    /** Монолит пишет деньги как Decimal128 (spring.data.mongodb.representation.big-decimal); строку тоже понимаем. */
    static BigDecimal money(Object value) {
        if (value instanceof Decimal128 decimal) {
            return decimal.bigDecimalValue();
        }
        if (value instanceof Number || value instanceof String) {
            return new BigDecimal(value.toString());
        }
        return null;
    }

    static Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }
}
