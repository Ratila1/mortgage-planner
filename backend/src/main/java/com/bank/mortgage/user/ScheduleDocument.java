package com.bank.mortgage.user;

import com.bank.mortgage.calculator.ScheduleEntry;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Document(collection = "schedules")
public record ScheduleDocument(
        @Id String id,
        String scheduleHash,
        List<ScheduleEntry> entries,
        Instant createdAt
) {
    public static ScheduleDocumentBuilder builder() {
        return new ScheduleDocumentBuilder();
    }

    public static class ScheduleDocumentBuilder {
        private String id;
        private String scheduleHash;
        private List<ScheduleEntry> entries;
        private Instant createdAt = Instant.now();

        public ScheduleDocumentBuilder id(String id) { this.id = id; return this; }
        public ScheduleDocumentBuilder scheduleHash(String scheduleHash) { this.scheduleHash = scheduleHash; return this; }
        public ScheduleDocumentBuilder entries(List<ScheduleEntry> entries) { this.entries = entries; return this; }
        public ScheduleDocumentBuilder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public ScheduleDocument build() {
            return new ScheduleDocument(id, scheduleHash, entries, createdAt);
        }
    }
}