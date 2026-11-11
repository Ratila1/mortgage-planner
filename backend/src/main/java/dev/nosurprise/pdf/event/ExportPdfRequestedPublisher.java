package dev.nosurprise.pdf.event;

import dev.nosurprise.common.messaging.RabbitEventPublisher;
import dev.nosurprise.config.RabbitPdfConfig;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ExportPdfRequestedPublisher {

    private final RabbitEventPublisher publisher;

    public ExportPdfRequestedPublisher(RabbitEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ExportPdfRequested event) {
        publisher.publish(RabbitPdfConfig.EXPORT_PDF_REQUESTED_RK, event);
    }
}