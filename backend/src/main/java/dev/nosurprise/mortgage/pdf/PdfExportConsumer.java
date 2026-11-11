package dev.nosurprise.mortgage.pdf;

import dev.nosurprise.mortgage.config.RetryPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PdfExportConsumer {

    private static final Logger log = LoggerFactory.getLogger(PdfExportConsumer.class);

    private final PdfExportServiceImpl pdfExportService;
    private final RetryPublisher retryPublisher;

    @Value("${rabbitmq.exchange.pdf-export-retry}")
    private String retryExchange;

    @Value("${rabbitmq.routing-key.pdf-export-retry}")
    private String retryRoutingKey;

    @Value("${rabbitmq.retry.max-attempts:5}")
    private int maxRetryAttempts;

    @Value("${rabbitmq.retry.base-delay-ms:2000}")
    private int baseDelayMs;

    public PdfExportConsumer(PdfExportServiceImpl pdfExportService, RetryPublisher retryPublisher) {
        this.pdfExportService = pdfExportService;
        this.retryPublisher = retryPublisher;
    }

    @RabbitListener(queues = "${rabbitmq.queue.pdf-export}")
    public void handlePdfExport(Message message, PdfExportServiceImpl.PdfExportMessage payload) {
        log.info("Processing PDF export task {}", payload.taskId());
        pdfExportService.processExportTask(payload)
                .doOnError(e -> {
                    log.error("PDF export task {} failed, scheduling retry", payload.taskId(), e);
                    retryPublisher.republishToRetryExchange(message, retryExchange, retryRoutingKey, maxRetryAttempts, baseDelayMs);
                })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }
}







