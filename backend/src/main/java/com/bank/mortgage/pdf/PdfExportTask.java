package com.bank.mortgage.pdf;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "pdf_export_tasks")
public record PdfExportTask(
        @Id String id,
        String userId,
        String scenarioId,
        TaskStatus status,
        String filePath,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
) {
    public enum TaskStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public static PdfExportTaskBuilder builder() {
        return new PdfExportTaskBuilder();
    }

    public static class PdfExportTaskBuilder {
        private String id;
        private String userId;
        private String scenarioId;
        private TaskStatus status = TaskStatus.PENDING;
        private String filePath;
        private String errorMessage;
        private Instant createdAt = Instant.now();
        private Instant completedAt;

        public PdfExportTaskBuilder id(String id) { this.id = id; return this; }
        public PdfExportTaskBuilder userId(String userId) { this.userId = userId; return this; }
        public PdfExportTaskBuilder scenarioId(String scenarioId) { this.scenarioId = scenarioId; return this; }
        public PdfExportTaskBuilder status(TaskStatus status) { this.status = status; return this; }
        public PdfExportTaskBuilder filePath(String filePath) { this.filePath = filePath; return this; }
        public PdfExportTaskBuilder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public PdfExportTaskBuilder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public PdfExportTaskBuilder completedAt(Instant completedAt) { this.completedAt = completedAt; return this; }

        public PdfExportTask build() {
            return new PdfExportTask(id, userId, scenarioId, status, filePath, errorMessage, createdAt, completedAt);
        }
    }
}