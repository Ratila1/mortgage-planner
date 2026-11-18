package dev.nosurprise.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/** Ежедневная очистка: отправленные события outbox и старые отметки processed_events (§4.8). */
@Component
public class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxEventRepository repository;
    private final JdbcTemplate jdbc;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxCleanupJob(OutboxEventRepository repository, JdbcTemplate jdbc,
                            OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.outbox.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void cleanup() {
        Instant now = clock.instant();
        int outbox = repository.deletePublishedBefore(now.minus(properties.retention()));
        int processed = jdbc.update("DELETE FROM processed_events WHERE processed_at < ?",
                Timestamp.from(now.minus(properties.processedRetention())));
        log.info("outbox cleanup: {} published events, {} processed marks removed", outbox, processed);
    }
}
