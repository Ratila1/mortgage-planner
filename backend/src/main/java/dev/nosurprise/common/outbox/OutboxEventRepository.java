package dev.nosurprise.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Пачка неотправленных событий под блокировкой. SKIP LOCKED: несколько инстансов relay
     * не возьмут одну и ту же строку, и никто не ждёт чужую блокировку.
     */
    @Query(value = "SELECT * FROM outbox_events WHERE published_at IS NULL AND attempts < :maxAttempts "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("limit") int limit, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = "DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < :before",
            nativeQuery = true)
    int deletePublishedBefore(@Param("before") Instant before);
}
