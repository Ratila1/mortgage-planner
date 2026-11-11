package com.bank.mortgage.pdf;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

public interface PdfExportTaskRepository extends ReactiveMongoRepository<PdfExportTask, String> {
    Mono<PdfExportTask> findById(String id);
}