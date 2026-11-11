package com.bank.mortgage.pdf;

import reactor.core.publisher.Mono;

public interface PdfExportService {
    Mono<PdfExportTask> createExportTask(String userId, String scenarioId);
    Mono<PdfExportTask> getTaskStatus(String taskId);
}