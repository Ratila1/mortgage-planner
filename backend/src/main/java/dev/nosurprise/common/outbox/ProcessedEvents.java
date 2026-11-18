package dev.nosurprise.common.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Идемпотентность consumer-ов (MICROLOANS.md §4.8): отметка (consumer, eventId) вставляется
 * в той же транзакции, что и работа consumer-а. Откатилась работа — откатилась и отметка,
 * событие обработается при повторе.
 */
@Component
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** true — событие видим впервые и можно работать; false — уже обработано, пропускаем. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID eventId) {
        int inserted = jdbc.update("INSERT INTO processed_events (consumer, event_id) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", consumer, eventId);
        return inserted == 1;
    }
}
